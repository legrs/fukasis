// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
import FukasisCore
import UIKit

enum ImageUtil {
    /// 積算した RAW を確認用の JPEG にする (Android 版の stacked.jpg と同じく, 最小..最大を 0..255 にして色をつける)
    static func jpeg(from image: FloatImage, cfa: CFA, quality: CGFloat = 0.9) -> Data? {
        guard let cg = cgImage(from: image, cfa: cfa) else { return nil }
        return UIImage(cgImage: cg).jpegData(compressionQuality: quality)
    }

    static func cgImage(from image: FloatImage, cfa: CFA) -> CGImage? {
        let rgb = SpectrumCore.previewRGB(image: image, cfa: cfa)
        guard let provider = CGDataProvider(data: Data(rgb) as CFData) else { return nil }
        return CGImage(width: image.width, height: image.height, bitsPerComponent: 8, bitsPerPixel: 24,
                       bytesPerRow: image.width * 3, space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }

    /// 画像の x ∈ [x0, x1) と, 行 bandCenter (割合) を中心に高さ height の範囲を切り出す
    static func crop(_ image: CGImage, x0: Int, x1: Int, bandCenter: Double, height: Int) -> CGImage? {
        let lo = max(0, min(x0, image.width - 1))
        let hi = max(lo + 1, min(x1, image.width))
        let cy = Int(Double(image.height) * bandCenter)
        let y0 = max(0, min(cy - height / 2, image.height - 1))
        let h = min(height, image.height - y0)
        return image.cropping(to: CGRect(x: lo, y: y0, width: hi - lo, height: max(1, h)))
    }
}
