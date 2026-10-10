// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
#include "spectrum.hpp"
#include "wavelength_calib.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <limits>
#include <sstream>

namespace fk
{
    namespace
    {
        const double SIGMA_THRES = 3.0;
    }

    bool parseCfa(const std::string &name, Cfa &out)
    {
        std::string s;
        for (char c : name)
            s.push_back((char)std::toupper((unsigned char)c));
        if (s == "RGGB")
            out = Cfa::RGGB;
        else if (s == "GRBG")
            out = Cfa::GRBG;
        else if (s == "GBRG")
            out = Cfa::GBRG;
        else if (s == "BGGR")
            out = Cfa::BGGR;
        else if (s == "MONO")
            out = Cfa::Mono;
        else
            return false;
        return true;
    }

    const char *cfaName(Cfa cfa)
    {
        switch (cfa)
        {
        case Cfa::RGGB:
            return "RGGB";
        case Cfa::GRBG:
            return "GRBG";
        case Cfa::GBRG:
            return "GBRG";
        case Cfa::BGGR:
            return "BGGR";
        case Cfa::Mono:
            return "MONO";
        }
        return "MONO";
    }

    int bayerChannel(Cfa cfa, int x, int y)
    {
        if (cfa == Cfa::Mono)
            return 1;
        const char *pattern = cfaName(cfa);
        switch (pattern[(y & 1) * 2 + (x & 1)])
        {
        case 'B':
            return 0;
        case 'R':
            return 2;
        default:
            return 1;
        }
    }

    void bandRows(const SpectrumParams &p, int height, int &y1, int &y2)
    {
        const int center = (int)std::floor(height * p.bandCenter);
        y1 = std::max(0, center - p.bandWidth / 2);
        y2 = std::min(height, center + p.bandWidth / 2);
    }

    void columnChannels(const ImageView &img, int x, int y1, int y2, Cfa cfa, double out[3])
    {
        int count[3] = {0, 0, 0};
        double mean[3] = {0, 0, 0};
        double sigma[3] = {0, 0, 0};
        double sum[3] = {0, 0, 0};
        for (int y = y1; y < y2; y++)
        {
            int ch = bayerChannel(cfa, x, y);
            mean[ch] += (double)img.at(x, y);
            count[ch]++;
        }
        for (int ch = 0; ch < 3; ch++)
            if (count[ch] > 0)
                mean[ch] /= (double)count[ch];
        for (int y = y1; y < y2; y++)
        {
            int ch = bayerChannel(cfa, x, y);
            double d = (double)img.at(x, y) - mean[ch];
            sigma[ch] += d * d;
        }
        for (int ch = 0; ch < 3; ch++)
            if (count[ch] > 0)
                sigma[ch] = std::sqrt(sigma[ch] / (double)count[ch]);
        // sigma clipping (外れ値は数えない. 負の値は 0 にする)
        for (int y = y1; y < y2; y++)
        {
            int ch = bayerChannel(cfa, x, y);
            double v = (double)img.at(x, y);
            if (SIGMA_THRES * sigma[ch] < std::fabs(v - mean[ch]))
                count[ch]--;
            else
                sum[ch] += std::max(v, 0.0);
        }
        for (int ch = 0; ch < 3; ch++)
            out[ch] = sum[ch] / (double)std::max(count[ch], 1);
    }

    std::vector<double> columnProfile(const ImageView &img, const SpectrumParams &p)
    {
        int y1, y2;
        bandRows(p, img.height, y1, y2);
        std::vector<double> out(img.width > 0 ? (size_t)img.width : 0, 0.0);
        double ch[3];
        for (int x = 0; x < img.width; x++)
        {
            columnChannels(img, x, y1, y2, p.cfa, ch);
            out[x] = ch[0] + ch[1] + ch[2];
        }
        return out;
    }

    int detectBandCenter(const ImageView &img)
    {
        const int h = img.height;
        const int xEnd = std::max(1, (int)(img.width * 0.75));
        std::vector<double> rowSum(h > 0 ? (size_t)h : 0, 0.0);
        for (int y = 0; y < h; y++)
        {
            double s = 0;
            for (int x = 0; x < xEnd && x < img.width; x++)
                s += std::max(img.at(x, y), 0.0f);
            rowSum[y] = s;
        }
        // 2行周期の Bayer の段差をならす
        int best = -1;
        double bestVal = 0;
        for (int y = 2; y < h - 2; y++)
        {
            double s = rowSum[y - 2] + rowSum[y - 1] + rowSum[y] + rowSum[y + 1] + rowSum[y + 2];
            if (s > bestVal)
            {
                bestVal = s;
                best = y;
            }
        }
        return best;
    }

    bool cfaFromMetadata(const std::string &header, Cfa &out)
    {
        const std::string key = ", cfa ";
        size_t pos = header.rfind(key);
        if (pos == std::string::npos)
            return false;
        size_t begin = pos + key.size();
        size_t end = begin;
        while (end < header.size() && std::isalpha((unsigned char)header[end]))
            end++;
        return parseCfa(header.substr(begin, end - begin), out);
    }

    std::vector<std::string> splitLines(const std::string &text)
    {
        std::vector<std::string> lines;
        std::string cur;
        for (char c : text)
        {
            if (c == '\n')
            {
                lines.push_back(cur);
                cur.clear();
            }
            else if (c != '\r')
            {
                cur.push_back(c);
            }
        }
        if (!cur.empty())
            lines.push_back(cur);
        return lines;
    }

    bool isBlank(const std::string &s)
    {
        return s.find_first_not_of(" \t") == std::string::npos;
    }

    bool parseNumbers(const std::string &line, std::vector<double> &out)
    {
        out.clear();
        std::stringstream ss(line);
        std::string field;
        while (std::getline(ss, field, ','))
        {
            const char *begin = field.c_str();
            char *end = nullptr;
            errno = 0;
            double v = std::strtod(begin, &end);
            if (end == begin || errno == ERANGE || !std::isfinite(v))
                return false;
            while (*end == ' ' || *end == '\t')
                end++;
            if (*end != '\0')
                return false;
            out.push_back(v);
        }
        return !out.empty();
    }

    std::string makeSpectrum(const ImageView &img, int fol, const std::string &calibText,
                             const std::string &metadataText, const std::string &sensitivityText,
                             SpectrumParams params, std::string &csvOut)
    {
        csvOut.clear();
        const int h = img.height;
        const int w = img.width;
        if (img.data == nullptr || w <= 0 || h <= 0)
            return "画像を読み込めません";
        if (fol <= 0 || fol >= w)
            return "0次光の位置(fol)が画像の範囲外です: " + std::to_string(fol);
        if (!(params.wlMin < params.wlMax))
            return "出力する波長の範囲が不正です";

        std::vector<double> nums;

        // calibdata ----------------------------------------------------------
        std::vector<std::vector<double>> calibRows;
        for (const std::string &line : splitLines(calibText))
        {
            if (isBlank(line))
                continue;
            if (!parseNumbers(line, nums) || nums.size() < 2)
                return "校正データの形式が不正です: " + line;
            calibRows.push_back(nums);
            if (calibRows.size() == 2)
                break;
        }
        if (calibRows.size() < 2)
            return "校正データがない";
        // 1行目が距離, 2行目が波長. 列の数が校正点の数 (4 個以上を想定)
        const std::vector<double> &t_ref = calibRows[0];
        const std::vector<double> &c_ref = calibRows[1];
        if (t_ref.size() != c_ref.size())
            return "校正データの距離と波長の数が合いません";
        for (size_t j = 0; j < t_ref.size(); j++)
            for (size_t k = j + 1; k < t_ref.size(); k++)
                if (t_ref[j] == t_ref[k])
                    return "校正データの画素位置が重複しています";
        // t -> 波長 の対応. 4 点ならその 4 点を通る 3 次式, 5 点以上なら 3 次の最小二乗
        const wlcalib::Poly wl_fit = wlcalib::fit(t_ref, c_ref);
        if (!wl_fit.ok)
            return "校正データから波長を求められません";

        // observation infomation --------------------------------------------------------------
        std::vector<std::string> metaLines = splitLines(metadataText);
        if (metaLines.empty())
            return "めただだ、ないです";
        const std::string header = metaLines[0];
        // 撮影した端末のカラーフィルタ配列が記録されていればそれを使う
        Cfa recorded;
        if (cfaFromMetadata(header, recorded))
            params.cfa = recorded;

        // sensitivity curve --------------------------------------------------------------
        // 先頭2行はヘッダ. 各行 wavelength, b, g, r (5列目以降は無視)
        std::vector<std::string> lines = splitLines(sensitivityText);
        std::vector<std::array<double, 2>> sensit; // {wavelength, b+g+r}
        for (size_t i = 2; i < lines.size(); i++)
        {
            if (isBlank(lines[i]))
                continue;
            if (!parseNumbers(lines[i], nums) || nums.size() < 4)
                return "感度データの形式が不正です(" + std::to_string(i + 1) + "行目)";
            sensit.push_back({nums[0], nums[1] + nums[2] + nums[3]});
        }
        if (sensit.size() < 2)
            return "感度データが足りません";
        std::sort(sensit.begin(), sensit.end(),
                  [](const std::array<double, 2> &a, const std::array<double, 2> &b) { return a[0] < b[0]; });

        // accumulate 縦 =======================
        int y1, y2;
        bandRows(params, h, y1, y2);

        std::vector<double> pure[3];
        double ch[3];
        for (int x = fol; x > 0; x--)
        {
            columnChannels(img, x, y1, y2, params.cfa, ch);
            for (int c = 0; c < 3; c++)
                pure[c].push_back(ch[c]);
        }
        const int size = (int)pure[0].size();

        // 出力する範囲. 画素の固定範囲ではなく波長で決める. 波長が逆行する部分は含めない
        const wlcalib::Range out_range = wlcalib::outputRange(wl_fit, t_ref, size, params.wlMin, params.wlMax);

        // bとrの欠落を埋めて、minをget =======================
        double minv[3];
        std::fill(minv, minv + 3, std::numeric_limits<double>::max());
        for (int i = 1; i < size - 1; i++)
        {
            // bayer arrayにより欠落が生じるから (どの Bayer 配列でも, 1列に写るのは G と R/B の片方)
            for (int c : {0, 2})
            {
                if (pure[c][i] == 0)
                    pure[c][i] = (pure[c][i - 1] + pure[c][i + 1]) / 2;
            }
            if (out_range.lo <= i && i <= out_range.hi)
            {
                for (int c = 0; c < 3; c++)
                    minv[c] = std::min(minv[c], pure[c][i]);
            }
        }

        // wavelength,sensitivity calibration & get max =======================
        std::vector<double> wavelengths;
        std::vector<double> intensities;
        double maxv = 0;
        for (int i = out_range.lo; i <= out_range.hi; i++)
        {
            // t -> t_p (波長)
            const double t_p = wl_fit.at(i);
            if (!(params.wlMin < t_p && t_p < params.wlMax))
                continue;

            // 感度を線形補間 (表の範囲外は端の値を使う)
            auto hi = std::upper_bound(sensit.begin(), sensit.end(), t_p,
                                       [](double v, const std::array<double, 2> &e) { return v < e[0]; });
            double s;
            if (hi == sensit.begin())
            {
                s = sensit.front()[1];
            }
            else if (hi == sensit.end())
            {
                s = sensit.back()[1];
            }
            else
            {
                auto lo = hi - 1;
                s = (*lo)[1] + (t_p - (*lo)[0]) * ((*hi)[1] - (*lo)[1]) / ((*hi)[0] - (*lo)[0]);
            }
            if (!(s > 0))
                continue; // 感度0の波長は補正できない

            double bgr = 0;
            for (int c = 0; c < 3; c++)
            {
                double v = pure[c][i] - minv[c];
                if (v > 0)
                    bgr += v;
            }
            bgr /= s;
            maxv = std::max(maxv, bgr);
            wavelengths.push_back(t_p);
            intensities.push_back(bgr);
        }
        if (wavelengths.empty())
        {
            std::ostringstream msg;
            msg << params.wlMin << "-" << params.wlMax << "nm に入る点がありません (校正データと fol を確認してください)";
            return msg.str();
        }
        if (!(maxv > 0))
            return "スペクトルの強度が0です";

        // export ===========================
        std::ostringstream spectrum;
        spectrum << header << "\n";
        spectrum << "wavelength/nm,relative intensity(0.0 -- 1.0)" << "\n";
        for (size_t i = 0; i < wavelengths.size(); i++)
            spectrum << wavelengths[i] << "," << intensities[i] / maxv << "\n";
        csvOut = spectrum.str();
        return "";
    }

    void Stacker::reset(int width, int height)
    {
        width_ = std::max(width, 0);
        height_ = std::max(height, 0);
        count_ = 0;
        sum_.assign((size_t)width_ * (size_t)height_, 0.0);
    }

    std::string Stacker::addU16(const uint8_t *data, size_t rowStrideBytes, size_t byteSize)
    {
        if (data == nullptr)
            return "ぬるぽ";
        if (width_ <= 0 || height_ <= 0 || sum_.empty())
            return "えっと…empty…なん、ですけど…";
        if (rowStrideBytes < (size_t)width_ * 2)
            return "行の長さが画像の幅より短いです";
        if (byteSize < (size_t)(height_ - 1) * rowStrideBytes + (size_t)width_ * 2)
            return "サイズが小さすぎるんだよね";
        for (int y = 0; y < height_; y++)
        {
            const uint8_t *row = data + (size_t)y * rowStrideBytes;
            double *dst = sum_.data() + (size_t)y * (size_t)width_;
            for (int x = 0; x < width_; x++)
            {
                // ネイティブエンディアンの 16bit (memcpy で読むのでアラインメントを気にしなくてよい)
                uint16_t v;
                std::copy(row + 2 * (size_t)x, row + 2 * (size_t)x + 2, reinterpret_cast<uint8_t *>(&v));
                dst[x] += (double)v;
            }
        }
        count_++;
        return "";
    }

    bool Stacker::mean(std::vector<float> &out) const
    {
        if (count_ <= 0 || sum_.empty())
            return false;
        out.resize(sum_.size());
        for (size_t i = 0; i < sum_.size(); i++)
            out[i] = (float)(sum_[i] / (double)count_);
        return true;
    }

    void subtract(const float *a, const float *b, float *out, size_t n)
    {
        for (size_t i = 0; i < n; i++)
            out[i] = a[i] - b[i];
    }

    void previewRgb8(const ImageView &img, Cfa cfa, uint8_t *out)
    {
        if (img.width <= 0 || img.height <= 0)
            return;
        float lo = std::numeric_limits<float>::max();
        float hi = std::numeric_limits<float>::lowest();
        for (int y = 0; y < img.height; y++)
            for (int x = 0; x < img.width; x++)
            {
                lo = std::min(lo, img.at(x, y));
                hi = std::max(hi, img.at(x, y));
            }
        const double scale = hi > lo ? 255.0 / ((double)hi - (double)lo) : 0.0;
        auto norm = [&](float v) -> double { return ((double)v - (double)lo) * scale; };
        for (int y = 0; y < img.height; y++)
        {
            const int by = y & ~1;
            for (int x = 0; x < img.width; x++)
            {
                double rgb[3] = {0, 0, 0}; // R, G, B
                int n[3] = {0, 0, 0};
                if (cfa == Cfa::Mono)
                {
                    double v = norm(img.at(x, y));
                    rgb[0] = rgb[1] = rgb[2] = v;
                    n[0] = n[1] = n[2] = 1;
                }
                else
                {
                    // 2x2 のかたまりの中の R, G, B を使う (画像の端で欠ける分は数えない)
                    const int bx = x & ~1;
                    for (int dy = 0; dy < 2; dy++)
                        for (int dx = 0; dx < 2; dx++)
                        {
                            int xx = bx + dx, yy = by + dy;
                            if (xx >= img.width || yy >= img.height)
                                continue;
                            int ch = bayerChannel(cfa, xx, yy); // 0=B 1=G 2=R
                            int idx = 2 - ch;
                            rgb[idx] += norm(img.at(xx, yy));
                            n[idx]++;
                        }
                }
                uint8_t *p = out + ((size_t)y * (size_t)img.width + (size_t)x) * 3;
                for (int c = 0; c < 3; c++)
                {
                    double v = n[c] > 0 ? rgb[c] / n[c] : 0.0;
                    p[c] = (uint8_t)std::lround(std::min(255.0, std::max(0.0, v)));
                }
            }
        }
    }
}
