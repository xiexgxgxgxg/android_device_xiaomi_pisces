/*
 * Minimal audio HAL for the Xiaomi Mi 3 TD (pisces, Tegra 4 + CS42L73).
 *
 * The vendor HAL (audio.primary.tegra.so) is an Android 4.x HAL and cannot be
 * used on Android 9 - its open() fails with EINVAL because it waits for
 * services that no longer exist (media.audio_policy, nvaudioservice).  The
 * framework therefore ran the AOSP stub and there was no sound at all.
 *
 * This implements the parts that matter: a legacy audio_hw_device_t
 * (AUDIO_DEVICE_API_VERSION_2_0, which the android.hardware.audio@2.0 HIDL
 * wrapper still accepts) driving the CS42L73 through tinyalsa.
 *
 * The playback path on this board is
 *
 *     tegra I2S -> ES325 (port B/D) -> CS42L73 ASP -> ESL/SPK path -> speaker
 *
 * and the speaker hangs off the codec's ESL path:
 *
 *     Int Spk <- SPKLINEOUT <- SPKLO Amp <- ESL DAC <- ESL Mixer <- ESL-ASP Mux
 *
 * The "Int Spk" DAPM pin switch also drives gpio-108 (spkr_en), the external
 * speaker amplifier enable.  The mixer values below are the ones the vendor's
 * /system/etc/nvaudio_conf.xml uses for the music device and the speaker.
 */

#define LOG_TAG "audio_hw_pisces"

#include <errno.h>
#include <malloc.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <log/log.h>

#include <hardware/audio.h>
#include <hardware/hardware.h>
#include <system/audio.h>

#include <tinyalsa/asoundlib.h>

#define PISCES_CARD         0
#define PISCES_OUT_DEVICE   0
#define PISCES_IN_DEVICE    0

#define PISCES_OUT_RATE     44100
#define PISCES_OUT_CHANNELS 2
#define PISCES_PERIOD_SIZE  1024
#define PISCES_PERIOD_COUNT 4

struct pisces_audio_device {
    struct audio_hw_device device;
    pthread_mutex_t lock;
    struct mixer *mixer;
    bool route_speaker;
};

struct pisces_stream_out {
    struct audio_stream_out stream;
    struct pisces_audio_device *adev;
    struct pcm *pcm;
    uint32_t sample_rate;
    audio_channel_mask_t channel_mask;
    audio_format_t format;
    size_t frame_count;
};

struct pisces_stream_in {
    struct audio_stream_in stream;
    struct pisces_audio_device *adev;
    struct pcm *pcm;
    uint32_t sample_rate;
    audio_channel_mask_t channel_mask;
    audio_format_t format;
    size_t frame_count;
};

/* ------------------------------------------------------------------ mixer */

static void mixer_set_int(struct mixer *mixer, const char *name, int value) {
    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mixer, name);
    if (ctl == NULL) {
        ALOGW("mixer control '%s' not found", name);
        return;
    }
    if (mixer_ctl_set_value(ctl, 0, value) != 0) {
        ALOGW("cannot set '%s' to %d", name, value);
    }
}

static void mixer_set_enum(struct mixer *mixer, const char *name, const char *value) {
    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mixer, name);
    if (ctl == NULL) {
        ALOGW("mixer control '%s' not found", name);
        return;
    }
    if (mixer_ctl_set_enum_by_string(ctl, value) != 0) {
        ALOGW("cannot set '%s' to %s", name, value);
    }
}

/* Values from nvaudio_conf.xml: the music device path plus the speaker route. */
static void pisces_set_speaker_route(struct pisces_audio_device *adev) {
    struct mixer *m = adev->mixer;
    if (m == NULL) {
        return;
    }

    /* codec output path (music) */
    mixer_set_int(m, "Input PGA Analog Volume", 36);
    mixer_set_int(m, "Input Path Digital Volume", 108);
    mixer_set_int(m, "ASP-IP Left Volume", 63);
    mixer_set_int(m, "ASP-IP Right Volume", 63);
    mixer_set_int(m, "VSP-IP Left Volume", 63);
    mixer_set_int(m, "VSP-IP Right Volume", 63);
    mixer_set_int(m, "HL-ASP Left Volume", 63);
    mixer_set_int(m, "HL-ASP Right Volume", 63);
    mixer_set_int(m, "HL-VSP Left Volume", 63);
    mixer_set_int(m, "HL-VSP Right Volume", 63);
    mixer_set_int(m, "ESL-ASP Mono Volume", 63);
    mixer_set_int(m, "ESL-VSP Mono Volume", 63);
    mixer_set_int(m, "SPK-ASP Mono Volume", 63);
    mixer_set_enum(m, "VSPOUT Mono/Stereo Select", "Stereo");

    /* speaker device "on" */
    mixer_set_int(m, "Ear Speaker Digital Playback Volume", 188);
    mixer_set_int(m, "Speakerphone Digital Playback Volume", 204);
    mixer_set_int(m, "Call Mode Switch", 0);
    mixer_set_int(m, "Int Spk Switch", 1);
    mixer_set_int(m, "Headphone Switch", 0);
    mixer_set_int(m, "Earpiece Switch", 0);

    ALOGI("speaker route applied");
}

static void pisces_apply_route(struct pisces_audio_device *adev, bool speaker) {
    pthread_mutex_lock(&adev->lock);
    if (adev->mixer == NULL) {
        adev->mixer = mixer_open(PISCES_CARD);
        if (adev->mixer == NULL) {
            ALOGE("cannot open the mixer for card %d", PISCES_CARD);
        }
    }
    if (adev->mixer != NULL && (speaker || !adev->route_speaker)) {
        pisces_set_speaker_route(adev);
    }
    adev->route_speaker = speaker;
    pthread_mutex_unlock(&adev->lock);
}

/* -------------------------------------------------------------- out stream */

static uint32_t out_get_sample_rate(const struct audio_stream *stream) {
    return ((const struct pisces_stream_out *)stream)->sample_rate;
}

static int out_set_sample_rate(struct audio_stream *stream, uint32_t rate) {
    ((struct pisces_stream_out *)stream)->sample_rate = rate;
    return 0;
}

static size_t out_get_buffer_size(const struct audio_stream *stream) {
    const struct pisces_stream_out *out = (const struct pisces_stream_out *)stream;
    return out->frame_count * audio_stream_out_frame_size(&out->stream);
}

static audio_channel_mask_t out_get_channels(const struct audio_stream *stream) {
    return ((const struct pisces_stream_out *)stream)->channel_mask;
}

static audio_format_t out_get_format(const struct audio_stream *stream) {
    return ((const struct pisces_stream_out *)stream)->format;
}

static int out_set_format(struct audio_stream *stream, audio_format_t format) {
    ((struct pisces_stream_out *)stream)->format = format;
    return 0;
}

static int out_standby(struct audio_stream *stream) {
    struct pisces_stream_out *out = (struct pisces_stream_out *)stream;
    if (out->pcm != NULL) {
        pcm_close(out->pcm);
        out->pcm = NULL;
    }
    return 0;
}

static int out_dump(const struct audio_stream *stream, int fd) {
    (void)stream;
    (void)fd;
    return 0;
}

static int out_set_parameters(struct audio_stream *stream, const char *kvpairs) {
    (void)stream;
    (void)kvpairs;
    return 0;
}

static char *out_get_parameters(const struct audio_stream *stream, const char *keys) {
    (void)stream;
    (void)keys;
    return strdup("");
}

static uint32_t out_get_latency(const struct audio_stream_out *stream) {
    const struct pisces_stream_out *out = (const struct pisces_stream_out *)stream;
    if (out->pcm == NULL) {
        return 0;
    }
    return (pcm_get_buffer_size(out->pcm) * 1000) / out->sample_rate;
}

static int out_set_volume(struct audio_stream_out *stream, float left, float right) {
    (void)stream;
    (void)left;
    (void)right;
    return 0;
}

static ssize_t out_write(struct audio_stream_out *stream, const void *buffer, size_t bytes) {
    struct pisces_stream_out *out = (struct pisces_stream_out *)stream;

    if (out->pcm == NULL) {
        return -ENODEV;
    }

    int ret = pcm_write(out->pcm, buffer, bytes);
    if (ret != 0) {
        ALOGE("pcm_write failed: %d (%s)", ret, pcm_get_error(out->pcm));
        return -EIO;
    }
    return bytes;
}

static int out_get_render_position(const struct audio_stream_out *stream, uint32_t *dsp_frames) {
    (void)stream;
    *dsp_frames = 0;
    return -EINVAL;
}

static int out_get_next_write_timestamp(const struct audio_stream_out *stream,
                                        int64_t *timestamp) {
    (void)stream;
    *timestamp = 0;
    return -EINVAL;
}

static int out_add_audio_effect(const struct audio_stream *stream, effect_handle_t effect) {
    (void)stream;
    (void)effect;
    return 0;
}

static int out_remove_audio_effect(const struct audio_stream *stream, effect_handle_t effect) {
    (void)stream;
    (void)effect;
    return 0;
}

/* --------------------------------------------------------------- in stream */

static uint32_t in_get_sample_rate(const struct audio_stream *stream) {
    return ((const struct pisces_stream_in *)stream)->sample_rate;
}

static int in_set_sample_rate(struct audio_stream *stream, uint32_t rate) {
    ((struct pisces_stream_in *)stream)->sample_rate = rate;
    return 0;
}

static size_t in_get_buffer_size(const struct audio_stream *stream) {
    const struct pisces_stream_in *in = (const struct pisces_stream_in *)stream;
    return in->frame_count * audio_stream_in_frame_size(&in->stream);
}

static audio_channel_mask_t in_get_channels(const struct audio_stream *stream) {
    return ((const struct pisces_stream_in *)stream)->channel_mask;
}

static audio_format_t in_get_format(const struct audio_stream *stream) {
    return ((const struct pisces_stream_in *)stream)->format;
}

static int in_set_format(struct audio_stream *stream, audio_format_t format) {
    ((struct pisces_stream_in *)stream)->format = format;
    return 0;
}

static int in_standby(struct audio_stream *stream) {
    struct pisces_stream_in *in = (struct pisces_stream_in *)stream;
    if (in->pcm != NULL) {
        pcm_close(in->pcm);
        in->pcm = NULL;
    }
    return 0;
}

static int in_dump(const struct audio_stream *stream, int fd) {
    (void)stream;
    (void)fd;
    return 0;
}

static int in_set_parameters(struct audio_stream *stream, const char *kvpairs) {
    (void)stream;
    (void)kvpairs;
    return 0;
}

static char *in_get_parameters(const struct audio_stream *stream, const char *keys) {
    (void)stream;
    (void)keys;
    return strdup("");
}

static int in_set_gain(struct audio_stream_in *stream, float gain) {
    (void)stream;
    (void)gain;
    return 0;
}

static ssize_t in_read(struct audio_stream_in *stream, void *buffer, size_t bytes) {
    struct pisces_stream_in *in = (struct pisces_stream_in *)stream;

    if (in->pcm != NULL) {
        int ret = pcm_read(in->pcm, buffer, bytes);
        if (ret == 0) {
            return bytes;
        }
        ALOGE("pcm_read failed: %d (%s)", ret, pcm_get_error(in->pcm));
    }
    /* no capture route configured yet: hand back silence */
    memset(buffer, 0, bytes);
    return bytes;
}

static uint32_t in_get_input_frames_lost(struct audio_stream_in *stream) {
    (void)stream;
    return 0;
}

static int in_add_audio_effect(const struct audio_stream *stream, effect_handle_t effect) {
    (void)stream;
    (void)effect;
    return 0;
}

static int in_remove_audio_effect(const struct audio_stream *stream, effect_handle_t effect) {
    (void)stream;
    (void)effect;
    return 0;
}

/* ------------------------------------------------------------- hw device */

static int adev_open_output_stream(struct audio_hw_device *dev, audio_io_handle_t handle,
                                   audio_devices_t devices, audio_output_flags_t flags,
                                   struct audio_config *config, struct audio_stream_out **stream_out,
                                   const char *address) {
    (void)handle;
    (void)devices;
    (void)flags;
    (void)address;
    struct pisces_audio_device *adev = (struct pisces_audio_device *)dev;

    struct pisces_stream_out *out = calloc(1, sizeof(*out));
    if (out == NULL) {
        return -ENOMEM;
    }

    out->sample_rate = config->sample_rate != 0 ? config->sample_rate : PISCES_OUT_RATE;
    out->channel_mask = config->channel_mask != 0 ? config->channel_mask
                                                  : AUDIO_CHANNEL_OUT_STEREO;
    out->format = AUDIO_FORMAT_PCM_16_BIT;
    out->frame_count = PISCES_PERIOD_SIZE;

    struct pcm_config pcm_config = {
        .channels = PISCES_OUT_CHANNELS,
        .rate = out->sample_rate,
        .period_size = PISCES_PERIOD_SIZE,
        .period_count = PISCES_PERIOD_COUNT,
        .format = PCM_FORMAT_S16_LE,
        .start_threshold = PISCES_PERIOD_SIZE,
        .stop_threshold = PISCES_PERIOD_SIZE * PISCES_PERIOD_COUNT,
        .silence_threshold = 0,
        .avail_min = 1,
    };

    pisces_apply_route(adev, true);

    out->pcm = pcm_open(PISCES_CARD, PISCES_OUT_DEVICE, PCM_OUT, &pcm_config);
    if (out->pcm == NULL || !pcm_is_ready(out->pcm)) {
        ALOGE("cannot open pcm card %d device %d: %s", PISCES_CARD, PISCES_OUT_DEVICE,
              out->pcm != NULL ? pcm_get_error(out->pcm) : "pcm_open failed");
        if (out->pcm != NULL) {
            pcm_close(out->pcm);
        }
        free(out);
        return -EIO;
    }

    out->stream.common.get_sample_rate = out_get_sample_rate;
    out->stream.common.set_sample_rate = out_set_sample_rate;
    out->stream.common.get_buffer_size = out_get_buffer_size;
    out->stream.common.get_channels = out_get_channels;
    out->stream.common.get_format = out_get_format;
    out->stream.common.set_format = out_set_format;
    out->stream.common.standby = out_standby;
    out->stream.common.dump = out_dump;
    out->stream.common.set_parameters = out_set_parameters;
    out->stream.common.get_parameters = out_get_parameters;
    out->stream.common.add_audio_effect = out_add_audio_effect;
    out->stream.common.remove_audio_effect = out_remove_audio_effect;
    out->stream.get_latency = out_get_latency;
    out->stream.set_volume = out_set_volume;
    out->stream.write = out_write;
    out->stream.get_render_position = out_get_render_position;
    out->stream.get_next_write_timestamp = out_get_next_write_timestamp;
    out->adev = adev;

    *stream_out = &out->stream;
    ALOGI("output stream open: %u Hz, %u channels, %d frames",
          out->sample_rate, audio_channel_count_from_out_mask(out->channel_mask),
          (int)out->frame_count);
    return 0;
}

static void adev_close_output_stream(struct audio_hw_device *dev,
                                     struct audio_stream_out *stream) {
    (void)dev;
    struct pisces_stream_out *out = (struct pisces_stream_out *)stream;
    out_standby(&stream->common);
    free(out);
}

static int adev_open_input_stream(struct audio_hw_device *dev, audio_io_handle_t handle,
                                  audio_devices_t devices, struct audio_config *config,
                                  struct audio_stream_in **stream_in, audio_input_flags_t flags,
                                  const char *address, audio_source_t source) {
    (void)handle;
    (void)devices;
    (void)config;
    (void)flags;
    (void)address;
    (void)source;
    (void)dev;

    struct pisces_stream_in *in = calloc(1, sizeof(*in));
    if (in == NULL) {
        return -ENOMEM;
    }
    in->sample_rate = PISCES_OUT_RATE;
    in->channel_mask = AUDIO_CHANNEL_IN_STEREO;
    in->format = AUDIO_FORMAT_PCM_16_BIT;
    in->frame_count = PISCES_PERIOD_SIZE;

    in->stream.common.get_sample_rate = in_get_sample_rate;
    in->stream.common.set_sample_rate = in_set_sample_rate;
    in->stream.common.get_buffer_size = in_get_buffer_size;
    in->stream.common.get_channels = in_get_channels;
    in->stream.common.get_format = in_get_format;
    in->stream.common.set_format = in_set_format;
    in->stream.common.standby = in_standby;
    in->stream.common.dump = in_dump;
    in->stream.common.set_parameters = in_set_parameters;
    in->stream.common.get_parameters = in_get_parameters;
    in->stream.common.add_audio_effect = in_add_audio_effect;
    in->stream.common.remove_audio_effect = in_remove_audio_effect;
    in->stream.set_gain = in_set_gain;
    in->stream.read = in_read;
    in->stream.get_input_frames_lost = in_get_input_frames_lost;

    *stream_in = &in->stream;
    return 0;
}

static void adev_close_input_stream(struct audio_hw_device *dev,
                                    struct audio_stream_in *stream) {
    (void)dev;
    struct pisces_stream_in *in = (struct pisces_stream_in *)stream;
    in_standby(&stream->common);
    free(in);
}

static int adev_set_parameters(struct audio_hw_device *dev, const char *kvpairs) {
    (void)dev;
    (void)kvpairs;
    return 0;
}

static char *adev_get_parameters(const struct audio_hw_device *dev, const char *keys) {
    (void)dev;
    (void)keys;
    return strdup("");
}

static int adev_init_check(const struct audio_hw_device *dev) {
    (void)dev;
    return 0;
}

static int adev_set_voice_volume(struct audio_hw_device *dev, float volume) {
    (void)dev;
    (void)volume;
    return 0;
}

static int adev_set_master_volume(struct audio_hw_device *dev, float volume) {
    (void)dev;
    (void)volume;
    return -ENOSYS;
}

static int adev_get_master_volume(struct audio_hw_device *dev, float *volume) {
    (void)dev;
    (void)volume;
    return -ENOSYS;
}

static int adev_set_master_mute(struct audio_hw_device *dev, bool muted) {
    (void)dev;
    (void)muted;
    return -ENOSYS;
}

static int adev_get_master_mute(struct audio_hw_device *dev, bool *muted) {
    (void)dev;
    (void)muted;
    return -ENOSYS;
}

static int adev_set_mode(struct audio_hw_device *dev, audio_mode_t mode) {
    (void)dev;
    (void)mode;
    return 0;
}

static int adev_set_mic_mute(struct audio_hw_device *dev, bool state) {
    (void)dev;
    (void)state;
    return 0;
}

static int adev_get_mic_mute(const struct audio_hw_device *dev, bool *state) {
    (void)dev;
    *state = false;
    return 0;
}

static size_t adev_get_input_buffer_size(const struct audio_hw_device *dev,
                                         const struct audio_config *config) {
    (void)dev;
    (void)config;
    return PISCES_PERIOD_SIZE * 2 * sizeof(int16_t);
}

static int adev_dump(const audio_hw_device_t *device, int fd) {
    (void)device;
    (void)fd;
    return 0;
}

static int adev_close(hw_device_t *device) {
    struct pisces_audio_device *adev = (struct pisces_audio_device *)device;
    if (adev->mixer != NULL) {
        mixer_close(adev->mixer);
    }
    pthread_mutex_destroy(&adev->lock);
    free(adev);
    return 0;
}

static int adev_open(const hw_module_t *module, const char *name, hw_device_t **device) {
    if (strcmp(name, AUDIO_HARDWARE_INTERFACE) != 0) {
        return -EINVAL;
    }

    struct pisces_audio_device *adev = calloc(1, sizeof(*adev));
    if (adev == NULL) {
        return -ENOMEM;
    }

    pthread_mutex_init(&adev->lock, NULL);

    adev->device.common.tag = HARDWARE_DEVICE_TAG;
    adev->device.common.version = AUDIO_DEVICE_API_VERSION_2_0;
    adev->device.common.module = (struct hw_module_t *)module;
    adev->device.common.close = adev_close;

    adev->device.init_check = adev_init_check;
    adev->device.set_voice_volume = adev_set_voice_volume;
    adev->device.set_master_volume = adev_set_master_volume;
    adev->device.get_master_volume = adev_get_master_volume;
    adev->device.set_master_mute = adev_set_master_mute;
    adev->device.get_master_mute = adev_get_master_mute;
    adev->device.set_mode = adev_set_mode;
    adev->device.set_mic_mute = adev_set_mic_mute;
    adev->device.get_mic_mute = adev_get_mic_mute;
    adev->device.set_parameters = adev_set_parameters;
    adev->device.get_parameters = adev_get_parameters;
    adev->device.get_input_buffer_size = adev_get_input_buffer_size;
    adev->device.open_output_stream = adev_open_output_stream;
    adev->device.close_output_stream = adev_close_output_stream;
    adev->device.open_input_stream = adev_open_input_stream;
    adev->device.close_input_stream = adev_close_input_stream;
    adev->device.dump = adev_dump;

    adev->mixer = mixer_open(PISCES_CARD);
    if (adev->mixer == NULL) {
        ALOGE("cannot open the mixer for card %d", PISCES_CARD);
    } else {
        pisces_set_speaker_route(adev);
    }

    *device = &adev->device.common;
    ALOGI("pisces audio HAL opened");
    return 0;
}

static struct hw_module_methods_t hal_module_methods = {
    .open = adev_open,
};

struct audio_module HAL_MODULE_INFO_SYM = {
    .common =
            {
                    .tag = HARDWARE_MODULE_TAG,
                    .module_api_version = AUDIO_MODULE_API_VERSION_0_1,
                    .hal_api_version = HARDWARE_HAL_API_VERSION,
                    .id = AUDIO_HARDWARE_MODULE_ID,
                    .name = "pisces audio HAL",
                    .author = "LineageOS pisces",
                    .methods = &hal_module_methods,
            },
};
