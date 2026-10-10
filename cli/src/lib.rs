// SPDX-License-Identifier: MIT
//! FUKASIS-app の dark / calibration / csv / view 画面に相当する処理.
//! 計算はアプリ (共通コア core/) と同じ結果になるようにしてある.

// 数値計算の部分はアプリの C++ と見比べやすいよう, 添字のループのまま書いている.
// `!(a < b)` の形の比較は, NaN のときも C++ と同じ側に倒すためにわざとそうしている
#![allow(
    clippy::needless_range_loop,
    clippy::manual_is_multiple_of,
    clippy::neg_cmp_op_on_partial_ord
)]

pub mod calib;
pub mod filter;
pub mod image;
pub mod plot;
pub mod spectrum;
pub mod tiff;
pub mod util;
