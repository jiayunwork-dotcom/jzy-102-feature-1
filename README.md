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

## 服务端掌管的侵蚀演进（时间线 + 分支 + 河网稳态）

除了「前端来回搬运整张地图」的单轮侵蚀，工具还提供一层由**后端自己记着状态**
的演进能力（右侧「侵蚀演进」面板）：

1. 用当前地形**开启一段演进**，拿到一个可一直引用的链标识；
2. 每点一次**再冲刷一轮**，后端在自己保存的链尾地形上接着算，把结果作为新的一帧
   追加到链尾——前端只说「用这组侵蚀参数推进」，**不回传**任何高度数据；
3. 链上**每一帧都能单独取回**查看，帧序列清楚标出走到第几帧、每帧用了什么参数；
4. 可从**任意历史帧岔出新分支**：新分支以那一帧为起点独立往下走，
   之后追加的帧只属于它自己，原链的每一帧字节不变，两条链各走各的；
   哪条链是从哪条链的第几帧岔出来的，链信息里都可查到；
5. 任意一帧都能**导出成命名快照**单独留档（复用原有的快照体系）。

演进链是服务端运行期间的**活数据**（内存保存，进程重启即丢），但只要进程活着，
链、帧、分支关系就稳定存取得到。

### 汇流河网与稳态判定

推进演进时，后端持续维护一张与地形同尺寸的**累积汇流场**：每一轮雨滴沿坡移动、
逐格点累计流经量，再按累计总步数归一化。干流承接的上游来水多、数值高，
一条条河道在场上连成高值脉络，前端可把它**叠加显示**在三维地形上（青→亮蓝）。

每个帧同时保存两张场：

| 场 | 含义 | 用途 |
| --- | --- | --- |
| `roundFlow` 本轮流经场 | 该轮每个雨滴每一步当时所在格点的流经次数 | 守恒核对：全场之和 = 该轮雨滴实际步数之和 |
| `flowAccumulation` 累积汇流场 | 演进以来流经量按累计总步数的归一化平均（全场和为 1） | 河网可视化与稳态判定 |

**稳态判据**：相邻两帧累积汇流场的整体变化幅度（逐格点绝对偏差之和）小到
阈值（默认 `0.03`）以下，就认为河网趋于稳态；接口同时返回当前变化幅度，
演示者能看到还差多少。该幅度在参数不变的持续推进下随轮次非增、一路收窄。

## 仓库结构

```
backend/                          Java 17 + Spring Boot
  src/main/java/com/geoviz/terrain/
    noise/NoiseGenerator.java     多层 Perlin 噪声高度场生成（频率倍増、振幅衰减）
    noise/NoiseParams.java
    erosion/ErosionSimulator.java 雨滴式水力侵蚀核心（单线程、种子可控、质量守恒）
    erosion/ErosionParams.java
    erosion/DropletListener.java  雨滴生命周期监听（含 onTraverse 单步流经回调）
    erosion/ErosionResult.java / ErosionStats.java
    flow/FlowAccumulator.java     单轮汇流累加器：逐格点累计雨滴流经次数（守恒）
    flow/FlowField.java           持续维护的累积汇流场（逐轮累加、按总步数归一化）
    flow/ConvergenceDetector.java 河网稳态判定：相邻帧汇流场变化幅度 + 阈值结论
    evolution/EvolutionStore.java 演进状态管理：内存中的链/帧注册表、分支血缘
    evolution/EvolutionChain.java / EvolutionFrame.java
    evolution/EvolutionService.java  推进编排：取链尾地形→侵蚀→汇流→稳态→追加帧
    evolution/EvolutionNotFoundException.java
    snapshot/SnapshotStore.java   快照存取：内存键值存储，按名存取
    snapshot/Snapshot.java / SnapshotMeta.java
    validation/ParameterValidator.java  参数校验：计算前拦截非法参数
    validation/InvalidParameterException.java
    api/TerrainController.java    POST /api/terrain/generate · /api/terrain/erode
    api/EvolutionController.java  /api/evolutions：开启/推进/查询/分支/取帧/导出快照
    api/SnapshotController.java   POST/GET /api/snapshots[/{name}]
    api/ApiExceptionHandler.java  非法参数 → 400；演进链/帧不存在 → 404，均带具体原因
  src/test/java/...               锁定核心不变量的自动化测试（见下）
frontend/                         Vue 3 + Three.js + Vite
  src/components/TerrainViewer.vue   三维渲染：网格、轨道相机、方向光、分层着色
  src/components/FlowOverlay.vue     汇流河网叠加层（独立 Three.js 点层，贴地表）
  src/components/EvolutionPanel.vue  演进操作面：开启/推进/帧序列/分支/稳态进度
  src/components/ParameterPanel.vue  参数面板：滑块、按钮、快照入口
  src/terrainGeometry.js             三维网格共享几何约定（渲染器与叠加层对齐）
  src/api/terrainApi.js              与后端的全部请求交互
  src/App.vue                        状态编排（当前高度场、快照、演进链、统计）
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

**侵蚀演进四条钉死关系**（`EvolutionInvariantsTest`、`FlowModuleTest`、`EvolutionApiTest`）：

| 关系 | 测试 |
| --- | --- |
| **整链可复现**：同一起始地形按同一参数序列、同一随机种子逐轮重放，每帧地形/本轮流经场/累积汇流场逐比特一致，服务端记状态不引入种子外差异 | `sameInitialTerrainAndParamSequenceReplayBitIdenticalChain` |
| **分支隔离**：从历史帧岔出并在新分支推进后，原链该帧及其之后每帧地形与两张汇流场与岔出前字节相同，两条链各自推进结果不同 | `branchFromHistoryFrameDoesNotMutateOriginalChain` |
| **汇流守恒可解释**：每帧 `roundFlow` 全场之和 = 该轮全部雨滴实际步数之和（`stats.totalSteps`），且与裸模拟器独立重放该轮的总步数、逐格流经场完全一致（每一步恰好贡献当时所在格点一次） | `roundFlowTotalEqualsActualDropletStepsOfThatRound`、`FlowModuleTest.roundFlowSumEqualsTraverseCount` |
| **稳态单调靠拢**：参数不变持续推进，相邻帧累积汇流场变化幅度从第 2 轮起非增、一路收窄，跨过阈值才报稳态，不会越推越剧烈却报稳态 | `flowChangeNarrowsMonotonicallyAndConvergesOnlyAfterThreshold` |
| 引用不存在的链/帧（404）、越界帧序号岔分支（404）、推进参数非法（400）都在计算前带原因拒绝，非法推进不产生新帧 | `invalidReferencesAndParamsAreRejectedBeforeComputation`、`EvolutionApiTest` |

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
| POST | `/api/evolutions` | 用起始高度场开启一段演进，返回链信息（含第 0 帧） |
| GET | `/api/evolutions` | 列出全部演进链（含分支血缘、帧数量、稳态） |
| GET | `/api/evolutions/{id}` | 查一条链：帧数量、每帧参数与变化幅度、当前稳态结论 |
| POST | `/api/evolutions/{id}/advance` | 在服务端保存的当前状态上再冲刷一轮（只传侵蚀参数，不传高度场） |
| POST | `/api/evolutions/{id}/branch` | 从 `frameIndex` 指定的历史帧岔出一条独立分支 |
| GET | `/api/evolutions/{id}/frames/{n}` | 单独取回第 n 帧（地形 + 累积汇流场 + 本轮流经场） |
| POST | `/api/evolutions/{id}/frames/{n}/snapshot` | 把第 n 帧导出成命名快照 |

参数非法（分辨率越界、层数/衰减系数不合理、速率为负、高度场长度不符等）
一律在计算前返回 `400 {"error": "具体原因"}`；引用不存在的演进链或帧、
从越界帧序号岔分支返回 `404 {"error": "具体原因"}`。

### 稳态判据为什么能单调收窄

累积汇流场是逐轮流经分布 qₜ 按总步数加权的平均：
`flowₜ = (Σₖ mₖ·qₖ) / Mₜ`。相邻两帧之差满足
`changeₜ = (mₜ/Mₜ)·‖qₜ − flowₜ₋₁‖₁ ≤ 2·mₜ/Mₜ`。每轮雨滴数量不变时 mₜ 基本恒定、
系数 mₜ/Mₜ 随轮次严格递减，因此变化幅度的上界一路收窄，实测值也随之单调下降
（在 64² 上对 8 组不同种子组合、128² 上连续 20 轮均为零回升），直至跨过阈值。
这也是为什么「单轮的流经场」会有雨云采样噪声、而**累积平均场**才是河网趋稳的
正确度量——单轮守恒量单独由 `roundFlow` 承担并核对。
