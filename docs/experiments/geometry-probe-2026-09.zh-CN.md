[English](geometry-probe-2026-09.md) | **简体中文**

# 实验记录：几何地面探针（2026-09-24）

[`../local-navigation-roadmap.zh-CN.md`](../local-navigation-roadmap.zh-CN.md) §5 里地面几何方案的依
据。只在 PC 上做，没有真机。脚本：
[`scripts/experiments/geometry_probe/`](../../scripts/experiments/geometry_probe/)。原始汇总：
[`results/summary-2026-09-24.txt`](../../scripts/experiments/geometry_probe/results/summary-2026-09-24.txt)，
路沿逐帧结果：[`results/curb-2026-09-24.json`](../../scripts/experiments/geometry_probe/results/curb-2026-09-24.json)。
不提交任何模型或数据集。

## 问题

能不能用单目相对深度做出不依赖类别的地面判断？三条路里哪条站得住：(1) 只用相对视差；(2) 重力 +
假设相机高度；(3) ARCore？第 3 条在 PC 上跑不了，只做了书面评估。

## 环境

- WSL Ubuntu，Python 3.12.3，只用 CPU（每个解释器 8 线程；三组实验同时跑、共用 CPU，所以耗时只
  作参考，没有用于任何决定）。
- `ai-edge-litert` 2.2.0、`numpy` 2.5.3、`opencv-python-headless` 5.0.0.93。
- 导出环境：`torch` 2.13.0、`transformers` 5.17.0、`litert-torch` 0.9.4。

## 模型（实际运行文件的 sha256）

| 文件 | 来源 | sha256 |
|---|---|---|
| `pg_depth.tflite` | Project Guideline `vision/models/depth.tflite` @ `d268de6` | `55e7465a8470f8d3f96fcac0e03b42f1812c86bd79058eb767120e4b5bfc4e31` |
| `dav2s_wi8.tflite`（518×686，int8 权重） | `litert-community/depth-anything-v2-small` @ `178427e`，`tflite/depth_anything_v2_small_wi8_afp32.tflite` | `f74509422e4a9270a354b249a9193abdd4903354be63701262238a7f4b869611` |
| `dav2s_392x518.tflite`（fp32） | 用 `export_dav2.py 392 518` 从 `depth-anything/Depth-Anything-V2-Small-hf` @ `5426e4f` 导出 | `f0e3714c3c5ae7b6ee541cf8102d1a97265c785a716004be29b28a8b104285ce` |
| `dav2s_266x350.tflite`（fp32） | `export_dav2.py 266 350`，同一版本 | `165b33832d97524d7e877ae1d169bcb11432a83e2bca23f702de1f3077fe40ba` |

转换器报告的运算量：29.1 G（266×350）、66.1 G（392×518）、122.2 G（518×686）。

## 数据

- **DIODE 验证集**（`http://diode-dataset.s3.amazonaws.com/val.tar.gz`）：室内 325 帧 + 室外 446 帧，
  1024×768，激光深度 + 有效掩码，内参 fx = fy = 886.81、cx = 512、cy = 384。有 1 帧室内图因为找不到
  地面平面候选而被丢弃。分辨率对比每 3 帧取 1 帧。
- **Cityscapes8**（Ultralytics 的 8 帧样例，`aachen_000000…07`）：公开的相机内参，高度 1.22 m，俯仰
  0.038 rad；labelId 7（车道）和 8（人行道）。Cityscapes 限非商业用途，这里只用于本地测量。

## 方法

每帧在 8 像素网格上：

1. **真值地面平面**：把图像下部 45% 的激光深度反投影成点；RANSAC 找法向与相机向下方向夹角在 35°
   以内的平面，支持点最多的作为地面（试过"最低平面"规则，因为会选中虚假平面而弃用）。`h_true` =
   相机到该平面的距离。真值地面 = 距离平面 5 cm 以内的像素。真值地面占下部区域 25% 以上且
   0.4 m < `h_true` < 3 m 的帧记为*地面可见*。
2. **平面线性度**：在真值地面像素上对 (u, v, 视差) 做最小二乘平面拟合，取相对残差中位数。
3. **路 1（P1）**：在下部区域对 (u, v, 视差) 做 RANSAC 自由平面拟合，内点阈值为相对误差 4%；预测地
   面与真值地面比较（IoU、精度、召回）。
4. **路 1 + 重力（P1g）**：RANSAC 拟合 `视差 = A·q + t`，`q = g · K⁻¹p`，`g` 取真值平面法向（理想重
   力），可叠加 2° 或 5° 扰动。
5. **路 2（P2）**：由 P1g 拟合得到公制深度 `Z = A·h / (d − t)`，`h = h_true · (1 + e)`，
   `e ∈ {−15%, 0, +15%}`。指标：10 m 内真值地面的相对深度误差中位数；真值障碍物（离地超过 0.25 m、
   6 m 以内）的相对深度误差；"离地超过 0.25 m"的像素精度/召回；1 m 宽走廊内最近障碍物的距离。
6. **路沿**：用公开俯仰角给出重力，在车道像素上拟合路面平面；15 m 内人行道像素的离地高度中位数；人
   行道残差的符号。

命令（`GEOPROBE_ROOT` 下放 `models/` 和 `data/val/`）：

```
python probe.py indoors && python probe.py outdoor
python res.py
CITYSCAPES8=/path/to/cityscapes8 python curb.py
python summ.py > results/summary.txt
```

## 结果（地面可见帧上的中位数）

| 指标 | DA V2 Small 518×686 | PG depth |
|---|---|---|
| 地面平面线性度，室外 / 室内 | 0.5% / 3.8% | 1.8% / 5.1% |
| P1 IoU，室外 / 室内 | 0.68 / 0.18 | 0.59 / 0.24 |
| P1 在无地面帧上报成地面的比例（真值约 5–16%） | 49% | 48–52% |
| P1g（理想重力）IoU，室外 / 室内 | 0.66 / 0.33 | 0.50 / 0.23 |
| P1g 在无地面帧上报成地面的比例 | 17–23% | 22–26% |
| P2 地面深度误差，高度正确，室外 / 室内 | 0.9% / 2.3% | 1.8% / 7.3% |
| P2 地面深度误差，高度 ±15% | ≈ 15% | ≈ 15–18% |
| P2 地面深度误差，重力偏 2° / 5°（高度正确，室外） | 5.7% / 11.7% | 5.5% / 13.0% |
| P2 障碍物深度误差，室外 / 室内 | 40% / 11% | 47% / 26% |
| "离地超过 0.25 m"的精度，室外 / 室内 | 1.00 / 0.98 | 1.00 / 0.72 |
| 路沿：人行道高于车道（符号）；高度（5 帧有人行道） | 93–100%；0.07–0.22 m | 100%；0.10–0.36 m，路面内点 22–60% |

分辨率（DA V2 Small，每 3 帧取 1 帧）：266×350 的地面线性度为室外 0.7% / 室内 5.2%，518×686 为
0.4% / 3.9%；高度正确时的地面深度误差分别为 1.0% / 5.9% 和 0.7% / 3.3%。

## 局限

- DIODE 是三脚架激光扫描仪（室外中位高度 2.4 m，室内约 1.2 m），不是手持手机；所以手机高度的影响按
  相对误差（h ± 15%）报告，而不是拿 1.3 m 去套 DIODE。
- 重力取的是真值平面法向（理想值）加合成噪声；真实 IMU 误差和时间对齐都没测。
- DIODE 里真实落差很少，"低于地面"的指标没有意义；台阶证据只来自 5 帧 Cityscapes。
- 走廊最近距离指标在预测和真值之间比较的是不同的像素集合，噪声大；只用来确认障碍物深度的偏差方向
  （偏远）。
- 室内地面可见帧不多（约 75 帧），因为 DIODE 室内视角大多是水平的。
