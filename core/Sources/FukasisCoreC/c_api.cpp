// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
#include "include/fukasis_core.h"

#include "spectrum.hpp"
#include "tiff.hpp"

#include <cstdlib>
#include <cstring>
#include <exception>
#include <string>

namespace
{
    char *dupString(const std::string &s)
    {
        char *p = (char *)std::malloc(s.size() + 1);
        if (p != nullptr)
            std::memcpy(p, s.c_str(), s.size() + 1);
        return p;
    }

    // 空文字列 (成功) なら NULL
    char *errorOrNull(const std::string &err)
    {
        return err.empty() ? nullptr : dupString(err);
    }

    fk::Cfa toCfa(int cfa)
    {
        if (cfa < FK_CFA_RGGB || cfa > FK_CFA_MONO)
            return fk::Cfa::Mono;
        return (fk::Cfa)cfa;
    }

    fk::SpectrumParams toParams(const fk_spectrum_params *p)
    {
        fk::SpectrumParams out;
        if (p == nullptr)
            return out;
        out.tMin = p->t_min;
        out.tMax = p->t_max;
        out.bandWidth = p->band_width;
        out.bandCenter = p->band_center;
        out.cfa = toCfa(p->cfa);
        out.wlMin = p->wl_min;
        out.wlMax = p->wl_max;
        return out;
    }

    fk::ImageView view(const float *img, int width, int height)
    {
        fk::ImageView v;
        v.data = img;
        v.width = img != nullptr ? width : 0;
        v.height = img != nullptr ? height : 0;
        v.stride = width > 0 ? (size_t)width : 0;
        return v;
    }
}

struct fk_stacker
{
    fk::Stacker stacker;
};

extern "C"
{
    fk_spectrum_params fk_default_spectrum_params(void)
    {
        fk::SpectrumParams d;
        fk_spectrum_params p;
        p.t_min = d.tMin;
        p.t_max = d.tMax;
        p.band_width = d.bandWidth;
        p.band_center = d.bandCenter;
        p.cfa = (int)d.cfa;
        p.wl_min = d.wlMin;
        p.wl_max = d.wlMax;
        return p;
    }

    int fk_cfa_parse(const char *name)
    {
        fk::Cfa cfa;
        if (name == nullptr || !fk::parseCfa(name, cfa))
            return -1;
        return (int)cfa;
    }

    const char *fk_cfa_name(int cfa)
    {
        return fk::cfaName(toCfa(cfa));
    }

    void fk_free(void *p)
    {
        std::free(p);
    }

    char *fk_make_spectrum(const float *img, int width, int height, int fol,
                           const char *calib_text, const char *metadata_text, const char *sensitivity_text,
                           const fk_spectrum_params *params, char **csv_out)
    {
        if (csv_out != nullptr)
            *csv_out = nullptr;
        try
        {
            std::string csv;
            std::string err = fk::makeSpectrum(view(img, width, height), fol,
                                               calib_text ? calib_text : "", metadata_text ? metadata_text : "",
                                               sensitivity_text ? sensitivity_text : "", toParams(params), csv);
            if (err.empty() && csv_out != nullptr)
                *csv_out = dupString(csv);
            return errorOrNull(err);
        }
        catch (const std::exception &e)
        {
            return dupString(std::string("例外: ") + e.what());
        }
    }

    int fk_column_profile(const float *img, int width, int height, const fk_spectrum_params *params, double *out)
    {
        if (img == nullptr || out == nullptr || width <= 0 || height <= 0)
            return -1;
        fk::ImageView v = view(img, width, height);
        std::vector<double> profile = fk::columnProfile(v, toParams(params));
        std::memcpy(out, profile.data(), profile.size() * sizeof(double));
        return fk::detectBandCenter(v);
    }

    void fk_tiff_encode_f32(const float *img, int width, int height, uint8_t **out, size_t *out_size)
    {
        std::vector<uint8_t> bytes;
        fk::encodeTiffF32(img, width, height, bytes);
        uint8_t *p = (uint8_t *)std::malloc(bytes.size());
        if (p != nullptr)
            std::memcpy(p, bytes.data(), bytes.size());
        if (out != nullptr)
            *out = p;
        else
            std::free(p);
        if (out_size != nullptr)
            *out_size = p != nullptr ? bytes.size() : 0;
    }

    char *fk_tiff_decode(const uint8_t *data, size_t size, float **out, int *width, int *height)
    {
        if (out != nullptr)
            *out = nullptr;
        try
        {
            std::vector<float> pixels;
            int w = 0, h = 0;
            std::string err = fk::decodeTiff(data, size, pixels, w, h);
            if (!err.empty())
                return dupString(err);
            float *p = (float *)std::malloc(pixels.size() * sizeof(float));
            if (p == nullptr)
                return dupString("メモリが足りません");
            std::memcpy(p, pixels.data(), pixels.size() * sizeof(float));
            if (out != nullptr)
                *out = p;
            else
                std::free(p);
            if (width != nullptr)
                *width = w;
            if (height != nullptr)
                *height = h;
            return nullptr;
        }
        catch (const std::exception &e)
        {
            return dupString(std::string("例外: ") + e.what());
        }
    }

    fk_stacker *fk_stacker_new(int width, int height)
    {
        try
        {
            fk_stacker *s = new fk_stacker();
            s->stacker.reset(width, height);
            return s;
        }
        catch (const std::exception &)
        {
            return nullptr;
        }
    }

    void fk_stacker_free(fk_stacker *s)
    {
        delete s;
    }

    char *fk_stacker_add_u16(fk_stacker *s, const uint8_t *data, size_t row_stride_bytes, size_t byte_size)
    {
        if (s == nullptr)
            return dupString("ぬるぽ");
        return errorOrNull(s->stacker.addU16(data, row_stride_bytes, byte_size));
    }

    int fk_stacker_count(const fk_stacker *s)
    {
        return s != nullptr ? s->stacker.count() : 0;
    }

    int fk_stacker_mean(const fk_stacker *s, float *out)
    {
        if (s == nullptr || out == nullptr)
            return 0;
        try
        {
            std::vector<float> mean;
            if (!s->stacker.mean(mean))
                return 0;
            std::memcpy(out, mean.data(), mean.size() * sizeof(float));
            return 1;
        }
        catch (const std::exception &)
        {
            return 0;
        }
    }

    void fk_subtract(const float *a, const float *b, float *out, size_t n)
    {
        fk::subtract(a, b, out, n);
    }

    void fk_preview_rgb8(const float *img, int width, int height, int cfa, uint8_t *out)
    {
        if (img == nullptr || out == nullptr)
            return;
        fk::previewRgb8(view(img, width, height), toCfa(cfa), out);
    }
}
