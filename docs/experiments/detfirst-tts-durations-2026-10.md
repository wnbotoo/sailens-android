**English** | [简体中文](detfirst-tts-durations-2026-10.zh-CN.md)

# Experiment note: Guidance speech durations (det-first D1.7, 2026-10-22)

How long each Guidance sentence takes to say on the recording phone. The speaker model (D1.4) and
every prompt's time budget rest on these numbers; until now they were an estimate (1.2–1.6 s, plan
§1.2) and the speaker model's 1.4 s default.

## Method

Debug tool (Settings → Diagnostics → Speech durations), build `8ed38d4`. Every message key, with
and without the NEAR prefix where it can carry one, plus the two status notices: 71 sentences per
language, each spoken 3 times through an engine of its own configured as Guidance configures its
engine (accessibility usage, speech content, the user's speech rate). Duration is the engine's
`onStart` → `onDone`, timed on the engine thread; latency is `speak()` → `onStart`.

- Phone: OnePlus CPH2747 (SM8850), Android SDK 36; engine `com.google.android.tts`; speech rate
  **1.30** (the user's Guidance setting).
- Quiet room, nothing else playing. Durations at another rate scale roughly inversely with it; the
  table is valid for this engine and rate only.

## Results

All 426 utterances finished; none failed. The engine is very repeatable: the three repetitions of a
sentence differ by 13–15 ms (median), 97 ms at most. Speech starts 3–10 ms after `speak()`.

| | Chinese | English |
|---|---|---|
| all sentences, median of medians | 1 814 ms | 1 471 ms |
| obstacle prompt, plain (median, range) | 1 495 ms (1 134–1 877) | 1 412 ms (834–1 668) |
| obstacle prompt with NEAR prefix | 2 113 ms (1 864–2 495) | 1 933 ms (1 354–2 313) |
| what the NEAR prefix adds (median) | +621 ms | +520 ms |
| "vehicle ahead" / with NEAR | 1 193 / 1 952 ms | 912 / 1 421 ms |
| "person ahead" / with NEAR | 1 232 / 1 970 ms | 834 / 1 354 ms |
| "path blocked" | 1 054 ms | 795 ms |
| "complex path ahead" | 1 395 ms | 1 131 ms |
| "ground unrecognized" (longest prompt) | 3 414 ms | 2 953 ms |
| notice "guidance stopped" / "not responding" | 4 374 / 3 473 ms | 3 735 / 2 892 ms |

Observations (no decision is taken here):

- Plain central prompts in Chinese take about 1.2 s, inside the plan's estimate. **A NEAR prompt
  takes about 1.9–2.1 s in Chinese** -- longer than the estimate's upper end.
- At the plan's walking speed of 1.2 m/s, about 2.3–2.5 m is covered while a Chinese NEAR prompt is
  being spoken, more than today's 1.5 m NEAR distance. This bears on Q4 (NEAR at 2.0 m in the
  det-first experiment) and on D2's thresholds; it is recorded here as arithmetic, not as a finding
  about any recorded hazard.
- No single figure fits: around the speaker model's flat 1.4 s default, short prompts take less
  ("person ahead" 834 ms in English, "path blocked" 1 054 ms in Chinese) and long ones more. Every
  Chinese NEAR obstacle prompt takes longer (1 864–2 495 ms); of the 27 plain obstacle prompts, 15
  take longer in English (834–1 668 ms overall) and 14 in Chinese (1 134–1 877 ms); English NEAR
  prompts take 1 354–2 313 ms, all but one longer. The speaker model should use the per-sentence
  durations of this table once calibration starts (D1.4).

## Appendix: the tables as the tool wrote them

### TTS durations, zh-CN

- measured 2026-10-22T09-05-06 on OnePlus CPH2747 (SDK 36)
- engine `com.google.android.tts`, speech rate 1.30
- duration = engine `onStart` to `onDone`, on the engine thread; latency = `speak()` to `onStart`

| key | NEAR | text | duration ms (each) | median | latency ms median | failed |
|---|---|---|---|---|---|---|
| `event_camera_blocked` |  | 镜头被遮挡 | 1219 / 1159 / 1176 | 1176 | 3 | 0 |
| `event_low_light` |  | 光线不足 | 1064 / 1055 / 1054 | 1055 | 4 | 0 |
| `event_blocked` |  | 前方不通 | 1045 / 1056 / 1054 | 1054 | 3 | 0 |
| `event_path_complex` |  | 前方路况复杂 | 1395 / 1414 / 1394 | 1395 | 4 | 0 |
| `event_narrowing` |  | 通道变窄 | 1065 / 1056 / 1054 | 1056 | 4 | 0 |
| `event_intersection` |  | 前方路口 | 1039 / 1035 / 1055 | 1039 | 4 | 0 |
| `event_traffic_light` |  | 前方红绿灯 | 1216 / 1197 / 1215 | 1215 | 4 | 0 |
| `event_road_warning` |  | 前方车道 | 1030 / 1020 / 1017 | 1020 | 3 | 0 |
| `event_road_warning_vehicle` |  | 前方有车 | 1074 / 1072 / 1099 | 1074 | 3 | 0 |
| `event_ground_to_road` |  | 前方车道 | 1032 / 1035 / 1035 | 1035 | 4 | 0 |
| `event_ground_to_terrain` |  | 路面变化 | 1027 / 1013 / 1015 | 1015 | 5 | 0 |
| `event_ground_to_sidewalk` |  | 路面变化 | 1013 / 1015 / 1017 | 1015 | 4 | 0 |
| `event_ground_to_indoor` |  | 路面变化 | 1017 / 1013 / 1014 | 1014 | 4 | 0 |
| `event_ground_change` |  | 路面变化 | 1014 / 1014 / 1014 | 1014 | 4 | 0 |
| `event_ground_unrecognized` |  | 识别不到地面，无法判断前方能否通行 | 3408 / 3415 / 3414 | 3414 | 4 | 0 |
| `event_obstacle_left` |  | 左侧有障碍 | 1140 / 1140 / 1155 | 1140 | 4 | 0 |
| `event_obstacle_left` | yes | 注意，左侧有障碍 | 1864 / 1874 / 1857 | 1864 | 4 | 0 |
| `event_obstacle_left_person` |  | 左侧有行人 | 1171 / 1176 / 1194 | 1176 | 5 | 0 |
| `event_obstacle_left_person` | yes | 注意，左侧有行人 | 1919 / 1916 / 1940 | 1919 | 3 | 0 |
| `event_obstacle_left_vehicle` |  | 左侧有车辆 | 1185 / 1194 / 1197 | 1194 | 4 | 0 |
| `event_obstacle_left_vehicle` | yes | 注意，左侧有车辆 | 1898 / 1915 / 1895 | 1898 | 4 | 0 |
| `event_obstacle_center` |  | 前方有障碍 | 1146 / 1155 / 1154 | 1154 | 5 | 0 |
| `event_obstacle_center` | yes | 注意，前方有障碍 | 1888 / 1878 / 1875 | 1878 | 5 | 0 |
| `event_obstacle_center_person` |  | 前方有行人 | 1220 / 1235 / 1232 | 1232 | 6 | 0 |
| `event_obstacle_center_person` | yes | 注意，前方有行人 | 1985 / 1970 / 1954 | 1970 | 4 | 0 |
| `event_obstacle_center_vehicle` |  | 前方有车辆 | 1175 / 1194 / 1193 | 1193 | 4 | 0 |
| `event_obstacle_center_vehicle` | yes | 注意，前方有车辆 | 1937 / 1955 / 1952 | 1952 | 7 | 0 |
| `event_obstacle_right` |  | 右侧有障碍 | 1117 / 1135 / 1134 | 1134 | 4 | 0 |
| `event_obstacle_right` | yes | 注意，右侧有障碍 | 1870 / 1893 / 1895 | 1893 | 6 | 0 |
| `event_obstacle_right_person` |  | 右侧有行人 | 1202 / 1216 / 1212 | 1212 | 5 | 0 |
| `event_obstacle_right_person` | yes | 注意，右侧有行人 | 1941 / 1956 / 1955 | 1955 | 4 | 0 |
| `event_obstacle_right_vehicle` |  | 右侧有车辆 | 1184 / 1193 / 1224 | 1193 | 5 | 0 |
| `event_obstacle_right_vehicle` | yes | 注意，右侧有车辆 | 1938 / 1935 / 1955 | 1938 | 4 | 0 |
| `event_obstacle_front_left` |  | 左前方有障碍 | 1270 / 1294 / 1275 | 1275 | 6 | 0 |
| `event_obstacle_front_left` | yes | 注意，左前方有障碍 | 2066 / 2074 / 2079 | 2074 | 5 | 0 |
| `event_obstacle_front_left_person` |  | 左前方有行人 | 1509 / 1515 / 1513 | 1513 | 5 | 0 |
| `event_obstacle_front_left_person` | yes | 注意，左前方有行人 | 2116 / 2133 / 2133 | 2133 | 6 | 0 |
| `event_obstacle_front_left_vehicle` |  | 左前方有车辆 | 1311 / 1336 / 1335 | 1335 | 4 | 0 |
| `event_obstacle_front_left_vehicle` | yes | 注意，左前方有车辆 | 2098 / 2094 / 2096 | 2096 | 4 | 0 |
| `event_obstacle_front_right` |  | 右前方有障碍 | 1254 / 1274 / 1275 | 1274 | 4 | 0 |
| `event_obstacle_front_right` | yes | 注意，右前方有障碍 | 2059 / 2071 / 2076 | 2071 | 5 | 0 |
| `event_obstacle_front_right_person` |  | 右前方有行人 | 1472 / 1496 / 1495 | 1495 | 4 | 0 |
| `event_obstacle_front_right_person` | yes | 注意，右前方有行人 | 2106 / 2113 / 2119 | 2113 | 5 | 0 |
| `event_obstacle_front_right_vehicle` |  | 右前方有车辆 | 1297 / 1316 / 1316 | 1316 | 4 | 0 |
| `event_obstacle_front_right_vehicle` | yes | 注意，右前方有车辆 | 2095 / 2092 / 2097 | 2095 | 6 | 0 |
| `event_obstacle_left_center` |  | 左侧和前方有障碍 | 1807 / 1815 / 1814 | 1814 | 4 | 0 |
| `event_obstacle_left_center` | yes | 注意，左侧和前方有障碍 | 2424 / 2434 / 2415 | 2424 | 5 | 0 |
| `event_obstacle_left_center_person` |  | 左侧和前方有行人 | 1877 / 1895 / 1873 | 1877 | 5 | 0 |
| `event_obstacle_left_center_person` | yes | 注意，左侧和前方有行人 | 2492 / 2495 / 2495 | 2495 | 4 | 0 |
| `event_obstacle_left_center_vehicle` |  | 左侧和前方有车辆 | 1866 / 1875 / 1875 | 1875 | 4 | 0 |
| `event_obstacle_left_center_vehicle` | yes | 注意，左侧和前方有车辆 | 2446 / 2457 / 2453 | 2453 | 4 | 0 |
| `event_obstacle_center_right` |  | 前方和右侧有障碍 | 1818 / 1815 / 1817 | 1817 | 4 | 0 |
| `event_obstacle_center_right` | yes | 注意，前方和右侧有障碍 | 2391 / 2394 / 2415 | 2394 | 4 | 0 |
| `event_obstacle_center_right_person` |  | 前方和右侧有行人 | 1850 / 1878 / 1872 | 1872 | 4 | 0 |
| `event_obstacle_center_right_person` | yes | 注意，前方和右侧有行人 | 2489 / 2496 / 2493 | 2493 | 4 | 0 |
| `event_obstacle_center_right_vehicle` |  | 前方和右侧有车辆 | 1867 / 1878 / 1872 | 1872 | 4 | 0 |
| `event_obstacle_center_right_vehicle` | yes | 注意，前方和右侧有车辆 | 2445 / 2459 / 2467 | 2459 | 4 | 0 |
| `event_obstacle_left_right` |  | 左右两侧都有障碍 | 1801 / 1762 / 1775 | 1775 | 4 | 0 |
| `event_obstacle_left_right` | yes | 注意，左右两侧都有障碍 | 2304 / 2311 / 2315 | 2311 | 4 | 0 |
| `event_obstacle_left_right_person` |  | 左右两侧有行人 | 1674 / 1678 / 1676 | 1676 | 4 | 0 |
| `event_obstacle_left_right_person` | yes | 注意，左右两侧有行人 | 2217 / 2235 / 2238 | 2235 | 4 | 0 |
| `event_obstacle_left_right_vehicle` |  | 左右两侧有车辆 | 1640 / 1637 / 1653 | 1640 | 3 | 0 |
| `event_obstacle_left_right_vehicle` | yes | 注意，左右两侧有车辆 | 2159 / 2168 / 2173 | 2168 | 10 | 0 |
| `event_obstacle_multiple` |  | 多个方向有障碍 | 1566 / 1575 / 1573 | 1573 | 4 | 0 |
| `event_obstacle_multiple` | yes | 注意，多个方向有障碍 | 2127 / 2155 / 2153 | 2153 | 5 | 0 |
| `event_obstacle_multiple_person` |  | 多个方向有行人 | 1673 / 1675 / 1675 | 1675 | 5 | 0 |
| `event_obstacle_multiple_person` | yes | 注意，多个方向有行人 | 2247 / 2253 / 2254 | 2253 | 4 | 0 |
| `event_obstacle_multiple_vehicle` |  | 多个方向有车辆 | 1613 / 1616 / 1615 | 1615 | 5 | 0 |
| `event_obstacle_multiple_vehicle` | yes | 注意，多个方向有车辆 | 2196 / 2195 / 2194 | 2195 | 5 | 0 |
| `notice_failed` |  | 辅助已停止：分析出错。重新启动前请不要依赖它。 | 4370 / 4395 / 4374 | 4374 | 4 | 0 |
| `notice_stalled` |  | 辅助没有响应。请先停下，确认后再走。 | 3463 / 3475 / 3473 | 3473 | 4 | 0 |

### TTS durations, en-US

- measured 2026-10-22T09-10-46 on OnePlus CPH2747 (SDK 36)
- engine `com.google.android.tts`, speech rate 1.30
- duration = engine `onStart` to `onDone`, on the engine thread; latency = `speak()` to `onStart`

| key | NEAR | text | duration ms (each) | median | latency ms median | failed |
|---|---|---|---|---|---|---|
| `event_camera_blocked` |  | Camera is covered | 1089 / 1034 / 1033 | 1034 | 5 | 0 |
| `event_low_light` |  | Too dark to see | 999 / 1015 / 1013 | 1013 | 5 | 0 |
| `event_blocked` |  | Path blocked | 795 / 795 / 796 | 795 | 4 | 0 |
| `event_path_complex` |  | Complex path ahead | 1120 / 1134 / 1131 | 1131 | 7 | 0 |
| `event_narrowing` |  | Path narrowing | 889 / 896 / 889 | 889 | 7 | 0 |
| `event_intersection` |  | Intersection ahead | 1057 / 1100 / 1067 | 1067 | 6 | 0 |
| `event_traffic_light` |  | Traffic light ahead | 1034 / 1035 / 1033 | 1034 | 5 | 0 |
| `event_road_warning` |  | Roadway ahead | 896 / 915 / 913 | 913 | 4 | 0 |
| `event_road_warning_vehicle` |  | Vehicle ahead | 906 / 916 / 917 | 916 | 4 | 0 |
| `event_ground_to_road` |  | Roadway ahead | 904 / 915 / 915 | 915 | 4 | 0 |
| `event_ground_to_terrain` |  | Surface changing | 1031 / 1034 / 1027 | 1031 | 6 | 0 |
| `event_ground_to_sidewalk` |  | Surface changing | 1039 / 1050 / 1033 | 1039 | 5 | 0 |
| `event_ground_to_indoor` |  | Surface changing | 1036 / 1032 / 1032 | 1032 | 5 | 0 |
| `event_ground_change` |  | Surface changing | 1034 / 1033 / 1034 | 1034 | 5 | 0 |
| `event_ground_unrecognized` |  | Can't see the ground, can't tell if the way ahead is clear | 2948 / 2953 / 2978 | 2953 | 5 | 0 |
| `event_obstacle_left` |  | Obstacle on the left | 1146 / 1154 / 1153 | 1153 | 5 | 0 |
| `event_obstacle_left` | yes | Careful, Obstacle on the left | 1787 / 1795 / 1794 | 1794 | 6 | 0 |
| `event_obstacle_left_person` |  | Person on the left | 1018 / 1032 / 1035 | 1032 | 5 | 0 |
| `event_obstacle_left_person` | yes | Careful, Person on the left | 1680 / 1654 / 1674 | 1674 | 5 | 0 |
| `event_obstacle_left_vehicle` |  | Vehicle on the left | 1097 / 1093 / 1095 | 1095 | 5 | 0 |
| `event_obstacle_left_vehicle` | yes | Careful, Vehicle on the left | 1728 / 1735 / 1734 | 1734 | 5 | 0 |
| `event_obstacle_center` |  | Obstacle ahead | 945 / 951 / 957 | 951 | 5 | 0 |
| `event_obstacle_center` | yes | Careful, Obstacle ahead | 1449 / 1457 / 1453 | 1453 | 5 | 0 |
| `event_obstacle_center_person` |  | Person ahead | 816 / 834 / 835 | 834 | 5 | 0 |
| `event_obstacle_center_person` | yes | Careful, Person ahead | 1360 / 1354 / 1353 | 1354 | 5 | 0 |
| `event_obstacle_center_vehicle` |  | Vehicle ahead | 912 / 914 / 912 | 912 | 5 | 0 |
| `event_obstacle_center_vehicle` | yes | Careful, Vehicle ahead | 1421 / 1436 / 1418 | 1421 | 4 | 0 |
| `event_obstacle_right` |  | Obstacle on the right | 1117 / 1131 / 1114 | 1117 | 5 | 0 |
| `event_obstacle_right` | yes | Careful, Obstacle on the right | 1738 / 1754 / 1754 | 1754 | 5 | 0 |
| `event_obstacle_right_person` |  | Person on the right | 967 / 973 / 976 | 973 | 4 | 0 |
| `event_obstacle_right_person` | yes | Careful, Person on the right | 1637 / 1656 / 1654 | 1654 | 5 | 0 |
| `event_obstacle_right_vehicle` |  | Vehicle on the right | 1046 / 1055 / 1056 | 1055 | 4 | 0 |
| `event_obstacle_right_vehicle` | yes | Careful, Vehicle on the right | 1676 / 1694 / 1694 | 1694 | 5 | 0 |
| `event_obstacle_front_left` |  | Obstacle on the front left | 1508 / 1513 / 1515 | 1513 | 5 | 0 |
| `event_obstacle_front_left` | yes | Careful, Obstacle on the front left | 2031 / 2034 / 2033 | 2033 | 5 | 0 |
| `event_obstacle_front_left_person` |  | Person on the front left | 1390 / 1394 / 1394 | 1394 | 5 | 0 |
| `event_obstacle_front_left_person` | yes | Careful, Person on the front left | 1855 / 1874 / 1878 | 1874 | 4 | 0 |
| `event_obstacle_front_left_vehicle` |  | Vehicle on the front left | 1446 / 1459 / 1451 | 1451 | 6 | 0 |
| `event_obstacle_front_left_vehicle` | yes | Careful, Vehicle on the front left | 1931 / 1933 / 1935 | 1933 | 6 | 0 |
| `event_obstacle_front_right` |  | Obstacle on the front right | 1451 / 1475 / 1471 | 1471 | 6 | 0 |
| `event_obstacle_front_right` | yes | Careful, Obstacle on the front right | 1957 / 1972 / 1976 | 1972 | 6 | 0 |
| `event_obstacle_front_right_person` |  | Person on the front right | 1340 / 1350 / 1354 | 1350 | 6 | 0 |
| `event_obstacle_front_right_person` | yes | Careful, Person on the front right | 1838 / 1855 / 1856 | 1855 | 5 | 0 |
| `event_obstacle_front_right_vehicle` |  | Vehicle on the front right | 1411 / 1410 / 1411 | 1411 | 7 | 0 |
| `event_obstacle_front_right_vehicle` | yes | Careful, Vehicle on the front right | 1887 / 1877 / 1901 | 1887 | 6 | 0 |
| `event_obstacle_left_center` |  | Obstacles on the left and ahead | 1584 / 1601 / 1577 | 1584 | 5 | 0 |
| `event_obstacle_left_center` | yes | Careful, Obstacles on the left and ahead | 2101 / 2094 / 2094 | 2094 | 5 | 0 |
| `event_obstacle_left_center_person` |  | Person on the left and ahead | 1426 / 1434 / 1435 | 1434 | 4 | 0 |
| `event_obstacle_left_center_person` | yes | Careful, Person on the left and ahead | 1920 / 1934 / 1935 | 1934 | 5 | 0 |
| `event_obstacle_left_center_vehicle` |  | Vehicle on the left and ahead | 1492 / 1494 / 1493 | 1493 | 5 | 0 |
| `event_obstacle_left_center_vehicle` | yes | Careful, Vehicle on the left and ahead | 1995 / 2013 / 2014 | 2013 | 6 | 0 |
| `event_obstacle_center_right` |  | Obstacles ahead and on the right | 1556 / 1555 / 1556 | 1556 | 4 | 0 |
| `event_obstacle_center_right` | yes | Careful, Obstacles ahead and on the right | 2089 / 2129 / 2094 | 2094 | 5 | 0 |
| `event_obstacle_center_right_person` |  | Person ahead and on the right | 1399 / 1412 / 1415 | 1412 | 4 | 0 |
| `event_obstacle_center_right_person` | yes | Careful, Person ahead and on the right | 1920 / 1932 / 1935 | 1932 | 7 | 0 |
| `event_obstacle_center_right_vehicle` |  | Vehicle ahead and on the right | 1493 / 1498 / 1494 | 1494 | 4 | 0 |
| `event_obstacle_center_right_vehicle` | yes | Careful, Vehicle ahead and on the right | 1994 / 1992 / 1994 | 1994 | 5 | 0 |
| `event_obstacle_left_right` |  | Obstacles on both sides | 1461 / 1522 / 1470 | 1470 | 4 | 0 |
| `event_obstacle_left_right` | yes | Careful, Obstacles on both sides | 2112 / 2114 / 2209 | 2114 | 5 | 0 |
| `event_obstacle_left_right_person` |  | People on both sides | 1268 / 1309 / 1294 | 1294 | 5 | 0 |
| `event_obstacle_left_right_person` | yes | Careful, People on both sides | 1947 / 1948 / 1974 | 1948 | 6 | 0 |
| `event_obstacle_left_right_vehicle` |  | Vehicles on both sides | 1447 / 1456 / 1456 | 1456 | 4 | 0 |
| `event_obstacle_left_right_vehicle` | yes | Careful, Vehicles on both sides | 2076 / 2074 / 2086 | 2076 | 5 | 0 |
| `event_obstacle_multiple` |  | Obstacles in multiple directions | 1656 / 1668 / 1674 | 1668 | 5 | 0 |
| `event_obstacle_multiple` | yes | Careful, Obstacles in multiple directions | 2303 / 2315 / 2313 | 2313 | 5 | 0 |
| `event_obstacle_multiple_person` |  | People in multiple directions | 1491 / 1514 / 1513 | 1513 | 5 | 0 |
| `event_obstacle_multiple_person` | yes | Careful, People in multiple directions | 2144 / 2135 / 2153 | 2144 | 6 | 0 |
| `event_obstacle_multiple_vehicle` |  | Vehicles in multiple directions | 1656 / 1655 / 1654 | 1655 | 5 | 0 |
| `event_obstacle_multiple_vehicle` | yes | Careful, Vehicles in multiple directions | 2232 / 2254 / 2234 | 2234 | 5 | 0 |
| `notice_failed` |  | Guidance stopped: analysis failed. Do not rely on it until restarted. | 3735 / 3734 / 3735 | 3735 | 5 | 0 |
| `notice_stalled` |  | Guidance is not responding. Stop and check before moving on. | 2891 / 2893 / 2892 | 2892 | 5 | 0 |
