<template>
  <div class="layout">
    <ParameterPanel
      ref="panel"
      :has-terrain="heightmap !== null"
      :busy="busy"
      :stats="stats"
      :error="error"
      :notice="notice"
      :snapshots="snapshots"
      v-model:light-azimuth="light.azimuth"
      v-model:light-elevation="light.elevation"
      @generate="onGenerate"
      @erode="onErode"
      @save-snapshot="onSaveSnapshot"
      @load-snapshot="onLoadSnapshot"
    />
    <main class="viewport">
      <TerrainViewer
        :heightmap="heightmap"
        :resolution="resolution"
        :light-azimuth="light.azimuth"
        :light-elevation="light.elevation"
        @ready="onViewerReady"
      />
      <FlowOverlay
        :viewer="viewer"
        :flow="displayFlow"
        :heightmap="heightmap"
        :resolution="resolution"
        :visible="showFlow && viewingFrameIndex >= 0"
      />
      <div class="flow-legend" v-if="showFlow && displayFlow">
        汇流河网（细支沟 → 干流：青 → 亮蓝）
      </div>
    </main>
    <EvolutionPanel
      :chains="chains"
      :chain="activeChain"
      :selected-chain-id="activeChainId"
      :viewing-frame-index="viewingFrameIndex"
      :has-terrain="heightmap !== null"
      :busy="busy"
      :error="evoError"
      :notice="evoNotice"
      v-model:show-flow="showFlow"
      @start="onStartEvolution"
      @advance="onAdvance"
      @branch="onBranch"
      @view-frame="onViewFrame"
      @select-chain="onSelectChain"
      @export-snapshot="onExportFrameSnapshot"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import ParameterPanel from './components/ParameterPanel.vue'
import TerrainViewer from './components/TerrainViewer.vue'
import EvolutionPanel from './components/EvolutionPanel.vue'
import FlowOverlay from './components/FlowOverlay.vue'
import * as api from './api/terrainApi.js'
import { defaultErosion, defaultLight, defaultNoise } from './defaults.js'

const panel = ref(null)

// 当前画面中的高度场及其分辨率（既用于原有「单轮侵蚀」，也用于开启演进）
const heightmap = ref(null)
const resolution = ref(0)

// 最近一次使用的参数（随快照一起保存）
const lastNoise = ref({ ...defaultNoise })
const lastErosion = ref({ ...defaultErosion })

const light = reactive({ ...defaultLight })
const snapshots = ref([])
const stats = ref(null)
const busy = ref(false) // false | 'generate' | 'erode' | 'evolve'
const error = ref('')
const notice = ref('')

// ---- 演进状态 ----
const viewer = ref(null)
const chains = ref([])
const activeChainId = ref('')
const activeChain = ref(null)      // GET chain 的链信息（帧元数据、稳态）
const viewingFrameIndex = ref(-1)  // 当前画面查看的帧；-1 表示看的是非演进地形
const activeFlow = ref(null)       // 当前查看帧的累积汇流场
const showFlow = ref(true)
const evoError = ref('')
const evoNotice = ref('')

const displayFlow = computed(() => (viewingFrameIndex.value >= 0 ? activeFlow.value : null))

function clearMessages() {
  error.value = ''
  notice.value = ''
}

function clearEvoMessages() {
  evoError.value = ''
  evoNotice.value = ''
}

async function run(taskName, fn, errTarget = error) {
  busy.value = taskName
  try {
    await fn()
  } catch (e) {
    errTarget.value = e.message
  } finally {
    busy.value = false
  }
}

function onViewerReady(ctx) {
  viewer.value = ctx
}

// ---- 原有能力：噪声生成 / 单轮侵蚀 / 快照（行为不变） ----

function onGenerate(noiseParams) {
  run('generate', async () => {
    const data = await api.generateTerrain(noiseParams)
    heightmap.value = data.heightmap
    resolution.value = data.resolution
    lastNoise.value = noiseParams
    stats.value = null
    // 新建的地形还不属于任何演进
    viewingFrameIndex.value = -1
    activeFlow.value = null
    notice.value = `已生成 ${data.resolution}×${data.resolution} 高度场`
  })
}

function onErode(erosionParams) {
  if (!heightmap.value) {
    error.value = '请先生成地形'
    return
  }
  run('erode', async () => {
    const data = await api.erodeTerrain(resolution.value, heightmap.value, erosionParams)
    heightmap.value = data.heightmap
    lastErosion.value = erosionParams
    stats.value = data.stats
    // 手工单轮侵蚀得到的是非演进的独立地形
    viewingFrameIndex.value = -1
    activeFlow.value = null
  })
}

function onSaveSnapshot({ name, noise, erosion }) {
  run('snapshot', async () => {
    await api.saveSnapshot({
      name,
      resolution: resolution.value,
      heightmap: heightmap.value,
      noise,
      erosion
    })
    notice.value = `快照「${name}」已保存`
    await refreshSnapshots()
  })
}

function onLoadSnapshot(name) {
  run('snapshot', async () => {
    const snap = await api.loadSnapshot(name)
    heightmap.value = snap.heightmap
    resolution.value = snap.resolution
    if (snap.noise) lastNoise.value = snap.noise
    if (snap.erosion) lastErosion.value = snap.erosion
    panel.value?.setParams(snap.noise, snap.erosion)
    stats.value = null
    viewingFrameIndex.value = -1
    activeFlow.value = null
    notice.value = `已加载快照「${name}」`
  })
}

// ---- 演进 ----

async function refreshChains() {
  chains.value = await api.listEvolutions()
}

async function refreshActiveChain() {
  if (!activeChainId.value) {
    activeChain.value = null
    return
  }
  activeChain.value = await api.getEvolution(activeChainId.value)
}

/** 加载某帧的完整数据到画面（地形 + 累积汇流场）。 */
async function loadFrameToView(chain, frameIndex) {
  const frame = await api.getEvolutionFrame(chain.id, frameIndex)
  heightmap.value = frame.heightmap
  resolution.value = chain.resolution ?? resolution.value
  activeFlow.value = frame.flowAccumulation
  viewingFrameIndex.value = frameIndex
  stats.value = frame.stats
  if (frame.erosion) lastErosion.value = frame.erosion
}

function onStartEvolution() {
  if (!heightmap.value) {
    evoError.value = '请先生成地形'
    return
  }
  run('evolve', async () => {
    clearEvoMessages()
    const currentParams = panel.value?.getParams()
    const chain = await api.startEvolution(
      resolution.value,
      heightmap.value,
      currentParams?.noise ?? lastNoise.value
    )
    activeChainId.value = chain.id
    await refreshChains()
    await refreshActiveChain()
    await loadFrameToView(chain, 0)
    evoNotice.value = `已开启演进 ${chain.id.slice(0, 8)}（第 0 帧）`
  }, evoError)
}

function onAdvance() {
  if (!activeChainId.value) {
    evoError.value = '请先开启一段演进'
    return
  }
  run('evolve', async () => {
    clearEvoMessages()
    const erosion = panel.value?.getParams()?.erosion ?? { ...lastErosion.value }
    const data = await api.advanceEvolution(activeChainId.value, erosion)
    lastErosion.value = erosion
    await refreshActiveChain()
    await loadFrameToView(activeChain.value, data.head.index)
    evoNotice.value = data.chain.converged
      ? `已追加第 ${data.head.index} 帧，河网已达稳态（变化 ${data.head.flowChange.toFixed(4)}）`
      : `已追加第 ${data.head.index} 帧，汇流变化 ${data.head.flowChange.toFixed(4)}`
    await refreshChains()
  }, evoError)
}

async function onViewFrame(frameIndex) {
  if (!activeChainId.value) return
  await run('evolve', async () => {
    clearEvoMessages()
    await refreshActiveChain()
    await loadFrameToView(activeChain.value, frameIndex)
    evoNotice.value = `查看第 ${frameIndex} 帧`
  }, evoError)
}

function onBranch(frameIndex) {
  if (!activeChainId.value) return
  run('evolve', async () => {
    clearEvoMessages()
    const branch = await api.branchEvolution(activeChainId.value, frameIndex)
    activeChainId.value = branch.id
    await refreshChains()
    await refreshActiveChain()
    await loadFrameToView(branch, 0)
    evoNotice.value = `已从第 ${frameIndex} 帧岔出新分支 ${branch.id.slice(0, 8)}`
  }, evoError)
}

async function onSelectChain(chainId) {
  await run('evolve', async () => {
    clearEvoMessages()
    activeChainId.value = chainId
    await refreshActiveChain()
    await loadFrameToView(activeChain.value, activeChain.value.headIndex)
    evoNotice.value = `切换到链 ${chainId.slice(0, 8)} 的链尾第 ${activeChain.value.headIndex} 帧`
  }, evoError)
}

function onExportFrameSnapshot({ frameIndex, name }) {
  if (!activeChainId.value) return
  run('evolve', async () => {
    clearEvoMessages()
    await api.exportEvolutionFrameSnapshot(activeChainId.value, frameIndex, name)
    await refreshSnapshots()
    evoNotice.value = `第 ${frameIndex} 帧已另存为快照「${name}」`
  }, evoError)
}

async function refreshSnapshots() {
  try {
    snapshots.value = await api.listSnapshots()
  } catch {
    // 列表失败不阻塞主流程
  }
}

onMounted(async () => {
  refreshSnapshots()
  onGenerate({ ...defaultNoise })
})
</script>

<style scoped>
.layout {
  display: flex;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
}
.viewport {
  flex: 1;
  min-width: 0;
  height: 100%;
  position: relative;
}
.flow-legend {
  position: absolute;
  left: 12px;
  bottom: 10px;
  color: #9fd0e8;
  font-size: 12px;
  background: rgba(16, 21, 28, 0.6);
  padding: 4px 9px;
  border-radius: 5px;
  pointer-events: none;
}
</style>
