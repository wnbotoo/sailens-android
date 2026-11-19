#include "world_grid.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace sailens {
namespace {

constexpr int kRows = 60;
constexpr int kColumns = 40;
constexpr int kRowWidth = 8;
constexpr int kAxisWidth = 8;
constexpr int kMaxEntries = 4194304;
constexpr int kMaxRaw = 64;
constexpr uint64_t kSign = uint64_t{1} << 63;
constexpr uint64_t kInfinity = UINT64_C(0x7ff0000000000000);
static_assert(std::numeric_limits<double>::is_iec559 && sizeof(double) == sizeof(uint64_t));

// Check lengths and aliases before acquiring the arrays. The kernels call no JNI or blocking
// operations while holding the views, except to acquire or release other primitive array views.
template <typename T>
class CriticalArray {
public:
    CriticalArray(JNIEnv* env, jarray array, jint releaseMode = JNI_ABORT)
        : env_(env), array_(array), releaseMode_(releaseMode),
          data_(array ? static_cast<T*>(env->GetPrimitiveArrayCritical(array, nullptr)) : nullptr) {}

    ~CriticalArray() {
        if (data_) env_->ReleasePrimitiveArrayCritical(array_, data_, releaseMode_);
    }

    CriticalArray(const CriticalArray&) = delete;
    CriticalArray& operator=(const CriticalArray&) = delete;
    T* data() const { return data_; }

private:
    JNIEnv* env_;
    jarray array_;
    jint releaseMode_;
    T* data_;
};

jlong tableEntries(jint width, jint height) {
    return (static_cast<jlong>(width) + 1) * (static_cast<jlong>(height) + 1);
}

#if defined(__aarch64__)
constexpr unsigned int kVectorClassCount = 32;
constexpr int kVectorPixels = 8;

int fillPassableBlock(const jint* labels, const jlong* words, int pixel, uint8x16x2_t classes,
                      const jint* previous, jint* output, int accumulated) {
    const uint32x4_t first = vreinterpretq_u32_s32(vld1q_s32(labels + pixel));
    const uint32x4_t second = vreinterpretq_u32_s32(vld1q_s32(labels + pixel + 4));
    // Saturation leaves negative and large class ids outside the lookup table.
    const uint8x8_t ids = vqmovn_u16(vcombine_u16(vqmovn_u32(first), vqmovn_u32(second)));
    const uint8x8_t passable = vqtbl2_u8(classes, ids);
    const int bit = pixel & 63;
    const int word = pixel >> 6;
    uint64_t mask = static_cast<uint64_t>(words[word]) >> bit;
    if (bit > 64 - kVectorPixels) mask |= static_cast<uint64_t>(words[word + 1]) << (64 - bit);
    static constexpr uint8_t laneBits[kVectorPixels] = {1, 2, 4, 8, 16, 32, 64, 128};
    const uint8x8_t visible = vtst_u8(vdup_n_u8(static_cast<uint8_t>(mask)), vld1_u8(laneBits));
    const uint8x8_t zero = vdup_n_u8(0);
    uint8x8_t prefix = vand_u8(passable, visible);
    prefix = vadd_u8(prefix, vext_u8(zero, prefix, 7));
    prefix = vadd_u8(prefix, vext_u8(zero, prefix, 6));
    prefix = vadd_u8(prefix, vext_u8(zero, prefix, 4));
    const uint16x8_t wide = vmovl_u8(prefix);
    const int32x4_t carry = vdupq_n_s32(accumulated);
    const auto lo = vreinterpretq_s32_u32(vmovl_u16(vget_low_u16(wide)));
    const auto hi = vreinterpretq_s32_u32(vmovl_u16(vget_high_u16(wide)));
    vst1q_s32(output, vaddq_s32(vld1q_s32(previous), vaddq_s32(lo, carry)));
    vst1q_s32(output + 4, vaddq_s32(vld1q_s32(previous + 4), vaddq_s32(hi, carry)));
    return accumulated + vget_lane_u8(prefix, 7);
}
#endif

template <bool upward>
double adjacent(double value) {
    uint64_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    const uint64_t magnitude = bits & ~kSign;
    if (magnitude > kInfinity || bits == (upward ? kInfinity : kSign | kInfinity)) return value;
    if (magnitude == 0) {
        bits = upward ? 1 : kSign | 1;
    } else if (((bits & kSign) == 0) == upward) {
        ++bits;
    } else {
        --bits;
    }
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

double roundDown(double value) { return adjacent<false>(value); }
double roundUp(double value) { return adjacent<true>(value); }

struct Bounds {
    double lowX, highX, lowY, highY;

    bool valid() const {
        return std::isfinite(lowX) && std::isfinite(highX) && std::isfinite(lowY) &&
               std::isfinite(highY) && lowX <= highX && lowY <= highY;
    }
};

using Corners = std::array<Bounds, kColumns + 1>;

bool projectRow(const double* row, const double* edges, Corners& corners) {
    // A nonprojectable row has NaN bases. It cannot contribute any of the four cell corners.
    if (!std::isfinite(row[0])) return false;
    for (int col = 0; col <= kColumns; ++col) {
        const double edge = edges[col];
        const int lowerSlope = edge >= 0.0 ? 4 : 5;
        const int upperSlope = edge >= 0.0 ? 5 : 4;
        corners[col] = {
            roundDown(row[0] + roundDown(row[lowerSlope] * edge)),
            roundUp(row[1] + roundUp(row[upperSlope] * edge)),
            roundDown(row[2] + roundDown(row[lowerSlope + 2] * edge)),
            roundUp(row[3] + roundUp(row[upperSlope + 2] * edge)),
        };
    }
    return true;
}

Bounds footprint(const Bounds& a, const Bounds& b, const Bounds& c, const Bounds& d) {
    return {
        std::min({a.lowX, b.lowX, c.lowX, d.lowX}),
        std::max({a.highX, b.highX, c.highX, d.highX}),
        std::min({a.lowY, b.lowY, c.lowY, d.lowY}),
        std::max({a.highY, b.highY, c.highY, d.highY}),
    };
}

uint64_t rawConflicts(const Bounds& area, const double* raw, int count) {
    uint64_t bits = 0;
    for (int i = 0; i < count; ++i, raw += 4) {
        if (area.lowX <= raw[1] && raw[0] <= area.highX && area.lowY <= raw[3] && raw[2] <= area.highY) {
            bits |= uint64_t{1} << i;
        }
    }
    return bits;
}

// Axis fields are the endpoints of scale, offset, ratio, crop, and support, as packed by
// WorldSemanticFootprint. Offset and crop are exact. Scale and ratio are nonnegative.
double mapLow(double value, const double* axis) {
    const double scaled = roundDown(value * axis[value >= 0.0 ? 0 : 1]);
    const double padded = roundDown(scaled + axis[2]);
    const double scored = roundDown(padded * axis[padded >= 0.0 ? 3 : 4]);
    return roundDown(scored - axis[5]);
}

double mapHigh(double value, const double* axis) {
    const double scaled = roundUp(value * axis[value >= 0.0 ? 1 : 0]);
    const double padded = roundUp(scaled + axis[2]);
    const double scored = roundUp(padded * axis[padded >= 0.0 ? 4 : 3]);
    return roundUp(scored - axis[5]);
}

bool withinSupport(const Bounds& area, const double* axes, int width, int height,
                   const jint* table, jboolean& accepted) {
    const double* x = axes;
    const double* y = axes + kAxisWidth;
    const Bounds mapped{mapLow(area.lowX, x), mapHigh(area.highX, x),
                        mapLow(area.lowY, y), mapHigh(area.highY, y)};
    if (!mapped.valid() || mapped.lowX <= 0.0 || mapped.highX >= width ||
            mapped.lowY <= 0.0 || mapped.highY >= height) {
        return false;
    }
    const int left = static_cast<int>(std::ceil(mapped.lowX)) - 1;
    const int right = static_cast<int>(std::floor(mapped.highX)) + 1;
    const int top = static_cast<int>(std::ceil(mapped.lowY)) - 1;
    const int bottom = static_cast<int>(std::floor(mapped.highY)) + 1;
    if (!(left >= x[6] && right <= x[7] && top >= y[6] && bottom <= y[7])) return false;

    const int stride = width + 1;
    const auto sum = static_cast<int64_t>(table[bottom * stride + right]) -
                     table[top * stride + right] - table[bottom * stride + left] + table[top * stride + left];
    accepted = sum == (right - left) * (bottom - top) ? JNI_TRUE : JNI_FALSE;
    return true;
}

}  // namespace

jboolean JNICALL fillWorldPassableTable(
        JNIEnv* env, jobject, jint width, jint height, jintArray labels, jlongArray effectiveWords,
        jbooleanArray passable, jintArray sums) {
    if (width <= 0 || height <= 0 || !labels || !effectiveWords || !passable || !sums ||
            env->IsSameObject(labels, sums)) {
        return JNI_FALSE;
    }
    const jlong entries = tableEntries(width, height);
    const jlong pixels = static_cast<jlong>(width) * height;
    if (entries > kMaxEntries || env->GetArrayLength(sums) < entries ||
            env->GetArrayLength(labels) != pixels || env->GetArrayLength(effectiveWords) < (pixels + 63) / 64) {
        return JNI_FALSE;
    }
    const auto classes = static_cast<unsigned int>(env->GetArrayLength(passable));
    const CriticalArray<jint> labelData(env, labels);
    if (!labelData.data()) return JNI_FALSE;
    const CriticalArray<jlong> words(env, effectiveWords);
    if (!words.data()) return JNI_FALSE;
    const CriticalArray<jboolean> lookup(env, passable);
    if (!lookup.data()) return JNI_FALSE;
    const CriticalArray<jint> sumsData(env, sums, 0);
    if (!sumsData.data()) return JNI_FALSE;

    const int stride = width + 1;
    jint* table = sumsData.data();
    std::fill(table, table + stride, 0);
#if defined(__aarch64__)
    std::array<uint8_t, kVectorClassCount> lookupBytes{};
    const bool vectorizable = classes <= kVectorClassCount;
    if (vectorizable) {
        for (unsigned int id = 0; id < classes; ++id) lookupBytes[id] = lookup.data()[id] != JNI_FALSE;
    }
    const uint8x16x2_t vectorLookup{{vld1q_u8(lookupBytes.data()), vld1q_u8(lookupBytes.data() + 16)}};
#endif
    int pixel = 0;
    for (int row = 1; row <= height; ++row) {
        jint* out = table + row * stride;
        const jint* previous = out - stride;
        out[0] = 0;
        int accepted = 0;
        int col = 1;
#if defined(__aarch64__)
        if (vectorizable) {
            for (; col <= width - kVectorPixels + 1; col += kVectorPixels, pixel += kVectorPixels) {
                accepted = fillPassableBlock(labelData.data(), words.data(), pixel, vectorLookup,
                                             previous + col, out + col, accepted);
            }
        }
#endif
        for (; col <= width; ++col, ++pixel) {
            const auto id = static_cast<unsigned int>(labelData.data()[pixel]);
            const auto word = static_cast<uint64_t>(words.data()[pixel >> 6]);
            if (id < classes && lookup.data()[id] && ((word >> (pixel & 63)) & 1u)) ++accepted;
            out[col] = previous[col] + accepted;
        }
    }
    return JNI_TRUE;
}

jint JNICALL classifyWorldGrid(
        JNIEnv* env, jobject, jdoubleArray rows, jdoubleArray edges, jdoubleArray rawBounds,
        jint rawCount, jdoubleArray axes, jint width, jint height, jintArray sums, jlongArray contacts,
        jlongArray conflicts, jbooleanArray surface) {
    constexpr int cells = kRows * kColumns;
    if (!rows || !edges || !rawBounds || !contacts || !conflicts || !surface ||
            rawCount < 0 || rawCount > kMaxRaw || env->IsSameObject(contacts, conflicts) ||
            env->GetArrayLength(rows) != (kRows + 1) * kRowWidth ||
            env->GetArrayLength(edges) != kColumns + 1 ||
            env->GetArrayLength(rawBounds) < rawCount * 4 ||
            env->GetArrayLength(contacts) != cells || env->GetArrayLength(conflicts) != cells ||
            env->GetArrayLength(surface) != cells) {
        return -1;
    }
    if (axes || sums) {
        if (!axes || !sums || width <= 0 || height <= 0 || env->GetArrayLength(axes) != 2 * kAxisWidth) return -1;
        const jlong entries = tableEntries(width, height);
        if (entries > kMaxEntries || env->GetArrayLength(sums) < entries) return -1;
    }

    const CriticalArray<jdouble> rowData(env, rows);
    if (!rowData.data()) return -1;
    const CriticalArray<jdouble> edgeData(env, edges);
    if (!edgeData.data()) return -1;
    const CriticalArray<jdouble> rawData(env, rawBounds);
    if (!rawData.data()) return -1;
    const CriticalArray<jdouble> axisData(env, axes);
    if (axes && !axisData.data()) return -1;
    const CriticalArray<jint> sumData(env, sums);
    if (sums && !sumData.data()) return -1;
    const CriticalArray<jlong> contactData(env, contacts);
    if (!contactData.data()) return -1;
    const CriticalArray<jlong> conflictData(env, conflicts, 0);
    if (!conflictData.data()) return -1;
    const CriticalArray<jboolean> surfaceData(env, surface, 0);
    if (!surfaceData.data()) return -1;

    std::fill(conflictData.data(), conflictData.data() + cells, 0);
    std::fill(surfaceData.data(), surfaceData.data() + cells, JNI_FALSE);
    Corners rowCorners[2];
    bool previousValid = projectRow(rowData.data(), edgeData.data(), rowCorners[0]);
    bool projectable = false;
    for (int row = 0; row < kRows; ++row) {
        const Corners& previous = rowCorners[row & 1];
        Corners& next = rowCorners[(row + 1) & 1];
        const bool nextValid = projectRow(rowData.data() + (row + 1) * kRowWidth, edgeData.data(), next);
        if (previousValid && nextValid) {
            for (int col = 0; col < kColumns; ++col) {
                if (!previous[col].valid() || !previous[col + 1].valid() ||
                        !next[col].valid() || !next[col + 1].valid()) continue;
                const Bounds area = footprint(previous[col], previous[col + 1], next[col], next[col + 1]);
                const int index = row * kColumns + col;
                const auto bits = rawConflicts(area, rawData.data(), rawCount);
                conflictData.data()[index] = static_cast<jlong>(bits);
                if (rawCount > 0) projectable = true;
                if (axes && bits == 0 && contactData.data()[index] == 0) {
                    projectable |= withinSupport(area, axisData.data(), width, height, sumData.data(), surfaceData.data()[index]);
                }
            }
        }
        previousValid = nextValid;
    }
    return projectable ? 1 : 0;
}

}  // namespace sailens
