// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
#include "tiff.hpp"

#include <cstring>

namespace fk
{
    namespace
    {
        enum Tag : uint16_t
        {
            ImageWidth = 256,
            ImageLength = 257,
            BitsPerSample = 258,
            Compression = 259,
            Photometric = 262,
            StripOffsets = 273,
            SamplesPerPixel = 277,
            RowsPerStrip = 278,
            StripByteCounts = 279,
            PlanarConfig = 284,
            SampleFormat = 339,
        };
        enum Type : uint16_t
        {
            SHORT = 3,
            LONG = 4,
        };

        void put16(std::vector<uint8_t> &out, size_t pos, uint16_t v)
        {
            out[pos] = (uint8_t)(v & 0xff);
            out[pos + 1] = (uint8_t)(v >> 8);
        }
        void put32(std::vector<uint8_t> &out, size_t pos, uint32_t v)
        {
            for (int i = 0; i < 4; i++)
                out[pos + i] = (uint8_t)(v >> (8 * i));
        }

        bool hostIsLittleEndian()
        {
            const uint16_t one = 1;
            uint8_t b;
            std::memcpy(&b, &one, 1);
            return b == 1;
        }

        class Reader
        {
        public:
            Reader(const uint8_t *data, size_t size) : data_(data), size_(size) {}
            bool little = true;

            bool has(size_t pos, size_t n) const { return pos <= size_ && n <= size_ - pos; }
            bool has64(uint64_t pos, uint64_t n) const { return pos <= size_ && n <= size_ - pos; }
            uint16_t u16(size_t pos) const
            {
                uint16_t a = data_[pos], b = data_[pos + 1];
                return little ? (uint16_t)(a | (b << 8)) : (uint16_t)((a << 8) | b);
            }
            uint32_t u32(size_t pos) const
            {
                uint32_t v = 0;
                for (int i = 0; i < 4; i++)
                {
                    uint32_t byte = data_[pos + (little ? i : 3 - i)];
                    v |= byte << (8 * i);
                }
                return v;
            }
            // サンプル1つを読む (bits: 8/16/32/64, format: 1=uint 2=int 3=float)
            double sample(size_t pos, int bits, int format) const
            {
                uint8_t buf[8];
                const int n = bits / 8;
                for (int i = 0; i < n; i++)
                    buf[i] = data_[pos + (little == hostIsLittleEndian() ? i : n - 1 - i)];
                if (format == 3)
                {
                    if (bits == 32)
                    {
                        float f;
                        std::memcpy(&f, buf, 4);
                        return f;
                    }
                    double d;
                    std::memcpy(&d, buf, 8);
                    return d;
                }
                switch (bits)
                {
                case 8:
                    return format == 2 ? (double)(int8_t)buf[0] : (double)buf[0];
                case 16:
                {
                    uint16_t v;
                    std::memcpy(&v, buf, 2);
                    return format == 2 ? (double)(int16_t)v : (double)v;
                }
                default:
                {
                    uint32_t v;
                    std::memcpy(&v, buf, 4);
                    return format == 2 ? (double)(int32_t)v : (double)v;
                }
                }
            }

        private:
            const uint8_t *data_;
            size_t size_;
        };

        struct Entry
        {
            uint16_t type = 0;
            uint32_t count = 0;
            size_t valuePos = 0; // 値そのもの (4バイト以内) か値へのオフセットが入っている位置
        };

        // タグの i 番目の値 (SHORT / LONG のみ対応)
        bool entryValue(const Reader &r, const Entry &e, uint32_t i, uint32_t &out)
        {
            if (e.type != SHORT && e.type != LONG)
                return false;
            if (i >= e.count)
                return false;
            const size_t sz = e.type == SHORT ? 2 : 4;
            size_t base = e.valuePos;
            if ((uint64_t)e.count * sz > 4)
                base = r.u32(e.valuePos); // 4バイトに収まらない値は別の場所に置かれる
            const uint64_t pos = (uint64_t)base + (uint64_t)i * sz;
            if (!r.has64(pos, sz))
                return false;
            out = e.type == SHORT ? r.u16((size_t)pos) : r.u32((size_t)pos);
            return true;
        }
    }

    void encodeTiffF32(const float *data, int width, int height, std::vector<uint8_t> &out)
    {
        const uint32_t w = width > 0 ? (uint32_t)width : 0;
        const uint32_t h = height > 0 ? (uint32_t)height : 0;
        const uint32_t dataBytes = w * h * 4;
        const uint16_t numEntries = 11;
        const uint32_t dataOffset = 8;
        const uint32_t ifdOffset = dataOffset + dataBytes + (dataBytes & 1); // IFD は偶数番地に置く
        const size_t total = (size_t)ifdOffset + 2 + (size_t)numEntries * 12 + 4;
        out.assign(total, 0);

        out[0] = 'I';
        out[1] = 'I';
        put16(out, 2, 42);
        put32(out, 4, ifdOffset);

        // 画素 (リトルエンディアンの float)
        size_t pos = dataOffset;
        for (size_t i = 0; i < (size_t)w * h; i++)
        {
            uint32_t bits;
            std::memcpy(&bits, &data[i], 4);
            put32(out, pos, bits);
            pos += 4;
        }

        size_t p = ifdOffset;
        put16(out, p, numEntries);
        p += 2;
        auto entry = [&](uint16_t tag, uint16_t type, uint32_t value) {
            put16(out, p, tag);
            put16(out, p + 2, type);
            put32(out, p + 4, 1);
            if (type == SHORT)
                put16(out, p + 8, (uint16_t)value);
            else
                put32(out, p + 8, value);
            p += 12;
        };
        // タグは番号順に並べる
        entry(ImageWidth, LONG, w);
        entry(ImageLength, LONG, h);
        entry(BitsPerSample, SHORT, 32);
        entry(Compression, SHORT, 1);
        entry(Photometric, SHORT, 1); // BlackIsZero
        entry(StripOffsets, LONG, dataOffset);
        entry(SamplesPerPixel, SHORT, 1);
        entry(RowsPerStrip, LONG, h);
        entry(StripByteCounts, LONG, dataBytes);
        entry(PlanarConfig, SHORT, 1);
        entry(SampleFormat, SHORT, 3); // IEEE float
        put32(out, p, 0);              // 次の IFD はない
    }

    std::string decodeTiff(const uint8_t *data, size_t size, std::vector<float> &out, int &width, int &height)
    {
        out.clear();
        width = height = 0;
        if (data == nullptr || size < 8)
            return "TIFF ではありません";
        Reader r(data, size);
        if (data[0] == 'I' && data[1] == 'I')
            r.little = true;
        else if (data[0] == 'M' && data[1] == 'M')
            r.little = false;
        else
            return "TIFF ではありません";
        if (r.u16(2) != 42)
            return "対応していない TIFF です (BigTIFF など)";

        const size_t ifd = r.u32(4);
        if (!r.has(ifd, 2))
            return "TIFF が壊れています";
        const uint16_t n = r.u16(ifd);
        if (!r.has(ifd + 2, (size_t)n * 12))
            return "TIFF が壊れています";

        Entry entries[12];
        const uint16_t wanted[12] = {ImageWidth, ImageLength, BitsPerSample, Compression, Photometric,
                                     StripOffsets, SamplesPerPixel, RowsPerStrip, StripByteCounts,
                                     PlanarConfig, SampleFormat, 0};
        bool found[12] = {};
        for (uint16_t i = 0; i < n; i++)
        {
            size_t pos = ifd + 2 + (size_t)i * 12;
            uint16_t tag = r.u16(pos);
            for (int k = 0; k < 11; k++)
            {
                if (wanted[k] == tag)
                {
                    entries[k].type = r.u16(pos + 2);
                    entries[k].count = r.u32(pos + 4);
                    entries[k].valuePos = pos + 8;
                    found[k] = true;
                }
            }
        }
        auto get = [&](int k, uint32_t def, uint32_t &v) -> bool {
            if (!found[k])
            {
                v = def;
                return true;
            }
            return entryValue(r, entries[k], 0, v);
        };

        uint32_t w, h, bits, comp, spp, rps, planar, fmt;
        if (!found[0] || !found[1] || !get(0, 0, w) || !get(1, 0, h))
            return "TIFF の大きさが読めません";
        if (!get(2, 1, bits) || !get(3, 1, comp) || !get(6, 1, spp) || !get(7, h, rps) ||
            !get(9, 1, planar) || !get(10, 1, fmt))
            return "TIFF のタグが読めません";
        if (comp != 1)
            return "圧縮された TIFF には対応していません";
        if (spp != 1)
            return "1チャネルの TIFF ではありません";
        if (!((fmt == 3 && (bits == 32 || bits == 64)) || ((fmt == 1 || fmt == 2) && (bits == 8 || bits == 16 || bits == 32))))
            return "対応していない画素形式です";
        if (w == 0 || h == 0 || w > 100000 || h > 100000)
            return "TIFF の大きさが不正です";
        if (!found[5] || !found[8])
            return "TIFF の画素の位置が書かれていません";
        if (rps == 0 || rps > h)
            rps = h;

        const size_t bytesPerSample = bits / 8;
        const size_t rowBytes = (size_t)w * bytesPerSample;
        const uint32_t strips = (h + rps - 1) / rps;
        if (entries[5].count < strips)
            return "TIFF の strip が足りません";

        out.assign((size_t)w * h, 0.0f);
        for (uint32_t s = 0; s < strips; s++)
        {
            uint32_t offset;
            if (!entryValue(r, entries[5], s, offset))
                return "TIFF の strip が読めません";
            const uint32_t rowStart = s * rps;
            const uint32_t rows = (rowStart + rps <= h) ? rps : h - rowStart;
            if (!r.has64(offset, (uint64_t)rowBytes * rows))
                return "TIFF が途中で切れています";
            for (uint32_t y = 0; y < rows; y++)
            {
                size_t rowPos = offset + (size_t)y * rowBytes;
                float *dst = out.data() + (size_t)(rowStart + y) * w;
                for (uint32_t x = 0; x < w; x++)
                    dst[x] = (float)r.sample(rowPos + (size_t)x * bytesPerSample, (int)bits, (int)fmt);
            }
        }
        width = (int)w;
        height = (int)h;
        return "";
    }
}
