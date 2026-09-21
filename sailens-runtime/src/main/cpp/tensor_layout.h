#ifndef SAILENS_TENSOR_LAYOUT_H
#define SAILENS_TENSOR_LAYOUT_H

// How a model's image-shaped tensor is laid out in memory.
//
// These values are the wire format of `ImageTensorLayout.nativeValue` in sailens-runtime, which
// is why the header lives here: one definition, shared by the vision and Guidance kernels that
// both walk semantic score tensors. A second copy would be free to drift, and a layout that
// drifts reads the scene through transposed axes without failing.
//
// Included by file path from the vision and Guidance CMake builds; no Gradle or Kotlin dependency
// is created in the wrong direction (architecture.md §6.10).

namespace sailens {

constexpr int kTensorLayoutNhwc = 0;
constexpr int kTensorLayoutNchw = 1;

inline bool isValidTensorLayout(int layout) {
    return layout == kTensorLayoutNhwc || layout == kTensorLayoutNchw;
}

/**
 * Maps a packed HWC index to the offset the tensor actually stores it at.
 *
 * @param packedHwcIndex pixelIndex * channels + channel
 */
inline int semanticScoreOffset(int packedHwcIndex, int pixelCount, int channels, int layout) {
    if (layout == kTensorLayoutNchw) {
        const int pixelIndex = packedHwcIndex / channels;
        const int channel = packedHwcIndex - pixelIndex * channels;
        return channel * pixelCount + pixelIndex;
    }
    return packedHwcIndex;
}

}  // namespace sailens

#endif  // SAILENS_TENSOR_LAYOUT_H
