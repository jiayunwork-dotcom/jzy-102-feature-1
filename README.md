# 地形水力侵蚀演示工具

面向地理科普的浏览器端小工具：用多层噪声生成光秃秃的隆起地表，再**真实逐颗模拟雨滴**
沿坡面下滑、侵蚀、搬运、沉积的物理过程，看着山脊被一点点啃出河谷与沟壑。
所有计算都在后端实时完成，不是预渲染动画；参数可以现场改、现场跑。

## 快速开始

```bash
docker compose up --build
```

然后打开 <http://localhost> 即可（前端静态资源由 nginx 托管，`/api` 反向代理到后端）。
若 80 端口被占用，把 `docker-compose.yml` 里前端的端口映射改成如 `"8080:80"` 再访问对应端口。

### 本地开发（不用 Docker）

```bash
# 后端（Java 17 + Maven）
cd backend && mvn spring-boot:run          # http://localhost:8080

# 前端（Node 20+），开发服务器已配置 /api 代理到本机 8080
cd frontend && npm install && npm run dev  # http://localhost:5173
```

### 运行后端测试

```bash
cd backend && mvn test
```

## 玩法说明

1. 左侧面板调噪声参数（分辨率、层数、频率倍增、振幅衰减、种子），点 **生成地形**；
2. 调侵蚀参数（雨滴数量、侵蚀率、沉积率、蒸发率等），点 **侵蚀一轮** —
   在当前地形上追加模拟一轮，可连点观察沟壑逐渐加深；
3. 拖动旋转、滚轮缩放、右键平移查看；光照方位角/高度角可调；
4. 地形按高度与坡度分层着色：低处偏绿、高处偏白、陡坡偏岩褐；
5. **快照另存**把当前高度场连同参数按名字存进内存，可随时从列表**加载**回看
   （进程重启即丢失，不做持久化）；
6. 每轮侵蚀后面板显示整图总高度变化 —— 质量守恒偏差只有浮点误差量级。

### 侵蚀演进时间线（右侧面板）

单轮侵蚀每次都要把整张地图发给后端、结果之间互不相干。**演进**把反复冲刷
变成一条由服务端掌管的、有先后顺序的链：

1. **开启演进**：以当前画面的地形作为第 0 帧，拿到一条链的标识；
2. **再推进一轮**：前端只说「用这组侵蚀参数再冲一轮」，后端在自己保存的
   当前帧上接着算，把新地形、汇流场、参数、统计追加为链尾新帧——高度数据
   不再往返浏览器；
3. **帧时间线**：能看到走到了第几帧、每一帧当时用的参数；点任意帧号即可
   跳回那帧查看（推进仍追加在链尾，不改变历史）；
4. **岔出分支**：从正在查看的任一历史帧岔出一条独立新链，换组参数走另一种
   走向；新链之后的帧只属于它自己，父链逐帧原样不动。链列表里写清了
   「从哪条链的第几帧岔出」；
5. **汇流场叠加**：勾选后在三维地形上叠加河网——每轮每颗雨滴每走一步给
   到达格点记一次流经，上游汇水越多颜色越亮，河道连成脉络；
6. **河网稳态**：随着固定参数下一轮轮推进，后端持续维护累计汇流场并报告
   相邻两帧的整体变化幅度；当它降到阈值（默认 0.02）以下即判为**已收敛到
   河网稳态**，面板同时给出当前变化幅度与「还差多少」；
7. **导出快照**：链上任意一帧都能另存为命名静态结果，单独留档。

演进链是服务端进程内的活数据，进程重启即丢；进程存活期间链、帧与分支
关系稳定存取。

## 仓库结构

```
backend/                          Java 17 + Spring Boot
  src/main/java/com/geoviz/terrain/
    noise/NoiseGenerator.java     多层 Perlin 噪声高度场生成（频率倍増、振幅衰减）
    noise/NoiseParams.java
    erosion/ErosionSimulator.java 雨滴式水力侵蚀核心（单线程、种子可控、质量守恒）
    erosion/ErosionParams.java
    erosion/DropletListener.java  雨滴生命周期监听（含逐位置回调，供汇流场统计）
    erosion/ErosionResult.java / ErosionStats.java
    flow/FlowAccumulator.java     汇流场：雨滴每步给到达格点记一次流经（独立模块）
    flow/FlowMetrics.java         汇流场整体变化幅度（归一化总变差距离）
    steady/SteadyStateDetector.java 河网稳态判定（变化幅度 ≤ 阈值；独立模块）
    steady/SteadyState.java
    evolution/EvolutionService.java 演进链状态管理：开启/推进/分支/查询/导出（独立模块）
    evolution/EvolutionChain.java / EvolutionFrame.java
    evolution/EvolutionNotFoundException.java
    snapshot/SnapshotStore.java   快照存取：内存键值存储，按名存取
    snapshot/Snapshot.java / SnapshotMeta.java
    validation/ParameterValidator.java  参数校验：计算前拦截非法参数
    validation/InvalidParameterException.java
    api/TerrainController.java    POST /api/terrain/generate · /api/terrain/erode
    api/SnapshotController.java   POST/GET /api/snapshots[/{name}]
    api/EvolutionController.java  演进链 REST（开启/推进/查链查帧/分支/稳态/导出）
    api/ApiExceptionHandler.java  非法参数 → 400、链/帧不存在 → 404，均带具体原因
  src/test/java/...               锁定核心不变量的自动化测试（见下）
frontend/                         Vue 3 + Three.js + Vite
  src/components/TerrainViewer.vue   三维渲染：网格、轨道相机、方向光、分层着色
  src/components/ParameterPanel.vue  参数面板：滑块、按钮、快照入口
  src/components/EvolutionPanel.vue  演进操作面：链/时间线/分支/稳态/导出（独立组件）
  src/components/FlowOverlay.vue     汇流场河网叠加：注入场景的半透明网格（独立组件）
  src/components/terrainContext.js   TerrainViewer 向叠加层暴露 Three.js 场景的注入键
  src/api/terrainApi.js              与后端的全部请求交互
  src/App.vue                        状态编排（当前高度场、快照、演进链、查看帧）
docker-compose.yml               一并拉起前后端，暴露 http://localhost
```

## 物理模型

**噪声地形**：若干层 2D Perlin 噪声叠加，第 i 层频率 = 基础频率 × lacunarityⁱ、
振幅 = persistenceⁱ，形成有主体起伏又带细节的地表。

**雨滴侵蚀**（每颗雨滴）：

1. 落在随机位置（落点由种子化的随机数决定）；
2. 每步用双线性插值取脚下高度与梯度，沿「惯性 + 最陡下降」混合方向前进一格；
3. 携带能力 = (流速 + 基础流量) × 水量 × 携带系数：
   含沙量超过携带能力（或在爬坡）→ 按沉积率把超出部分沉回地表；
   否则按侵蚀率从地表带走泥沙（以小刷子分摊，沟壑因此有宽度）；
4. 速度随下滑落差累积（speed² += 落差 × 重力），水量随路程蒸发
   （water ×= 1 − 蒸发率）；
5. 水量低于阈值、步数耗尽或滑出地图时消亡，消亡前把剩余泥沙全部摊回地表。

**汇流场（河网）**：演进每推进一轮，挂在模拟过程上的汇流统计器在每颗雨滴
成功走完一步后，给它到达的格点记一次流经。上游汇下来的水越多，格点数值
越高，河道便在这张场上连成高值脉络；整场合计恰好等于这一轮全部雨滴实际
走过的步数。链上再把各轮场逐轮累加为累计汇流场，作为河网是否稳定的判据
（见上文「汇流场与稳态判据」）。

## 自动化测试锁住的不变量

`backend/src/test/java/com/geoviz/terrain/`：

| 不变量 | 测试 |
| --- | --- |
| 只放大侵蚀率 → 高度方差更大（冲刷更狠、起伏更剧烈） | `ErosionInvariantsTest.higherErosionRateYieldsLargerHeightVariance` |
| 固定种子同参数跑两次 → 高度场逐比特一致 | `ErosionInvariantsTest.sameSeedAndParamsReproduceIdenticalHeightmap` |
| 雨滴水量单调不升，耗尽立即终止，不会"空水"继续侵蚀 | `ErosionInvariantsTest.dropletWaterIsMonotonicAndDepletionTerminatesDroplet` |
| 一轮模拟前后整图总高度和只差浮点误差量级（质量守恒） | `ErosionInvariantsTest.totalHeightIsConservedUpToFloatingPointError` |
| 噪声层数调多 → 地表梯度整体波动幅度上升 | `NoiseGeneratorTest.moreOctavesIncreaseGradientVariation` |
| 分辨率/层数/衰减系数越界、速率为负等 → 计算前带原因拒绝 | `ParameterValidatorTest`、`TerrainApiTest` |
| 快照按名存取、同名覆盖、列表元信息 | `SnapshotStoreTest`、`TerrainApiTest` |
| **演进逐帧可复现**：同初始场、同参数序列（含种子）重放，每一帧高度场与汇流场逐比特一致 | `EvolutionServiceTest.replayWithSameParamsAndSeedReproducesEveryFrameBitForBit` |
| **分支隔离**：岔出新分支并推进后，父链被岔帧及其后每一帧逐比特原样，帧数不变 | `EvolutionServiceTest.branchingAndAdvancingBranchLeavesParentChainUntouched` |
| **汇流场守恒可解释**：每帧逐轮汇流场总和恰好等于该轮雨滴实际总步数，且各格点为非负整数 | `FlowAccumulatorTest.flowFieldSumsExactlyToActualDropletSteps`、`EvolutionServiceTest.everyAdvancedFrameFlowSumsToItsActualDropletSteps` |
| **稳态单调靠拢**：累计汇流场变化幅度跨阈值前逐轮不增，跨过即判稳态且不再上穿；steady ⟺ change ≤ 阈值 | `SteadyStateConvergenceTest.flowChangeNarrowsMonotonicallyAndCrossesThresholdOnce` |
| 不存在的链/帧、越界帧序号、推进给非法参数 → 计算前带原因拒绝（404/400） | `EvolutionServiceTest`、`EvolutionApiTest` |
| 演进接口完整往返：开启→推进→查链/查帧→分支→稳态→导出快照 | `EvolutionApiTest.fullEvolutionLifecycleThroughApi` |

### 汇流场与稳态判据（实现说明）

- **逐轮汇流场**：挂在侵蚀过程上的 `FlowAccumulator` 在每颗雨滴**成功走完一步后**
  把到达格点的流经次数加一。滑出地图的那次尝试不构成实际一步、不计数，因此
  整场合计与 `ErosionStats.totalSteps` 严格相等（守恒）。
- **累计汇流场与稳态**：受驱耗散系统即便河网宏观稳定，单滴微观路径仍会逐轮
  抖动（直接比较逐轮场会被这种固有涨落主导）。链因此另维护一张逐轮累加的
  累计汇流场——对河网的运行时最佳估计；每多跑一轮，它只被稀释约 1/n，
  相邻两帧累计场的归一化总变差距离天然随轮次收窄到 0，低于阈值即判稳态。
  每帧的 `flowChange` 记的就是这个量。
- 阈值可经配置项 `terrain.steady-threshold`（默认 0.02）调整。

> 实现备注：携带能力刻意**不**直接乘落差（只通过速度积分间接体现坡度），
> 否则「坑越深 → 侵蚀越狠」的正反馈会让多轮模拟发散；侵蚀用刷子分摊、
> 中途沉积分双线性集中，是同时满足「沟壑连贯」「多轮有界」
> 「侵蚀率越高方差越大」与「质量严格守恒」的关键结构。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/terrain/generate` | 按噪声参数生成高度场 |
| POST | `/api/terrain/erode` | 在提交的高度场上追加一轮侵蚀，返回新高度场与统计 |
| POST | `/api/snapshots` | 命名保存当前高度场与参数 |
| GET | `/api/snapshots` | 列出全部快照（元信息） |
| GET | `/api/snapshots/{name}` | 取回某份快照的完整数据 |
| POST | `/api/evolution` | 以起始高度场开启演进链，返回链标识（201） |
| GET | `/api/evolution` | 列出全部演进链（帧数、分支血缘、稳态摘要） |
| GET | `/api/evolution/{chainId}` | 链详情：帧序、每帧参数/统计/汇流变化、分支血缘 |
| POST | `/api/evolution/{chainId}/advance` | 只提交侵蚀参数推进一轮，返回新帧与稳态结论 |
| GET | `/api/evolution/{chainId}/frames/{i}` | 单独取回第 i 帧（地形 + 逐轮汇流场 + 参数） |
| POST | `/api/evolution/{chainId}/branch` | 从指定帧岔出独立新分支（201） |
| GET | `/api/evolution/{chainId}/steady` | 河网稳态结论：steady、当前变化幅度、阈值 |
| POST | `/api/evolution/{chainId}/frames/{i}/snapshot` | 把第 i 帧导出为命名快照（201） |

参数非法（分辨率越界、层数/衰减系数不合理、速率为负、高度场长度不符等）
一律在计算前返回 `400 {"error": "具体原因"}`；引用不存在的演进链或帧、
从越界帧序号岔分支返回 `404 {"error": "具体原因"}`。
