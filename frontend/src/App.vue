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
      >
        <FlowOverlay
          v-if="showFlow"
          :heightmap="heightmap"
          :flowmap="flowmap"
          :resolution="resolution"
        />
      </TerrainViewer>
    </main>
    <EvolutionPanel
      :has-terrain="heightmap !== null"
      :busy="busy"
      :chains="chains"
      :active-chain-id="activeChainId"
      :active-chain="activeChain"
      :viewing-index="viewingFrame"
      v-model:show-flow="showFlow"
      :error="evoError"
      :notice="evoNotice"
      @start="onStartEvolution"
      @advance="onAdvance"
      @jump="onJumpToFrame"
      @branch="onBranch"
      @select-chain="onSelectChain"
      @export-snapshot="onExportFrame"
    />
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import ParameterPanel from './components/ParameterPanel.vue'
import TerrainViewer from './components/TerrainViewer.vue'
import EvolutionPanel from './components/EvolutionPanel.vue'
import FlowOverlay from './components/FlowOverlay.vue'
import * as api from './api/terrainApi.js'
import { defaultErosion, defaultLight, defaultNoise } from './defaults.js'

const panel = ref(null)

// 当前画面中的高度场及其分辨率
const heightmap = ref(null)
const resolution = ref(0)
// 当前画面叠加的逐轮汇流场（演进查看帧时非空）
const flowmap = ref(null)
const showFlow = ref(true)

// 最近一次使用的噪声参数（随演进/快照保存）
const lastNoise = ref({ ...defaultNoise })

const light = reactive({ ...defaultLight })
const snapshots = ref([])
const stats = ref(null)
const busy = ref(false) // false | 'generate' | 'erode' | 'evolve' | 'snapshot'
const error = ref('')
const notice = ref('')

// ---- 演进时间线状态 ----
const chains = ref([])            // 全部链摘要
const activeChainId = ref(null)  // 当前选中的链
const activeChain = ref(null)    // 当前链详情（帧元信息）
const viewingFrame = ref(0)     // 当前查看帧（可回看历史，不影响链尾）
const evoError = ref('')
const evoNotice = ref('')

function clearMessages() {
  error.value = ''
  notice.value = ''
}

function clearEvoMessages() {
  evoError.value = ''
  evoNotice.value = ''
}

async function run(taskName, fn) {
  busy.value = taskName
  clearMessages()
  clearEvoMessages()
  try {
    await fn()
  } catch (e) {
    // 演进相关的失败显示在演进面板，其余显示在参数面板
    if (taskName === 'evolve') {
      evoError.value = e.message
    } else {
      error.value = e.message
    }
  } finally {
    busy.value = false
  }
}

// 离开「画面与某条链某帧严格对应」的状态（手动生成/单轮侵蚀/加载快照后）
function detachFromChain() {
  activeChainId.value = null
  activeChain.value = null
  viewingFrame.value = 0
  flowmap.value = null
}

function onGenerate(noiseParams) {
  run('generate', async () => {
    const data = await api.generateTerrain(noiseParams)
    heightmap.value = data.heightmap
    resolution.value = data.resolution
    lastNoise.value = noiseParams
    stats.value = null
    detachFromChain()
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
    stats.value = data.stats
    detachFromChain()
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
    panel.value?.setParams(snap.noise, snap.erosion)
    stats.value = null
    detachFromChain()
    notice.value = `已加载快照「${name}」`
  })
}

// ---- 演进操作 ----

function onStartEvolution() {
  if (!heightmap.value) {
    evoError.value = '请先生成地形'
    return
  }
  run('evolve', async () => {
    const summary = await api.startEvolution(
      resolution.value, heightmap.value, lastNoise.value)
    activeChainId.value = summary.chainId
    await refreshChainDetail(summary.chainId)
    viewingFrame.value = 0
    flowmap.value = null // 第 0 帧尚未冲刷，汇流场为零
    await refreshChains()
    evoNotice.value = `已开启演进链 ${summary.chainId.slice(0, 8)}（第 0 帧）`
  })
}

function onAdvance() {
  if (!activeChainId.value) {
    evoError.value = '请先开启或选择一条演进链'
    return
  }
  run('evolve', async () => {
    const erosionParams = panel.value?.getErosionParams()
    if (!erosionParams) throw new Error('读取侵蚀参数失败')
    const data = await api.advanceEvolution(activeChainId.value, erosionParams)
    heightmap.value = data.heightmap
    flowmap.value = data.flow
    stats.value = data.stats
    viewingFrame.value = data.index
    await refreshChainDetail(activeChainId.value)
    await refreshChains()
    evoNotice.value = data.steady
      ? `已推进到第 ${data.index} 帧 —— 已收敛到河网稳态（变化幅度 ${data.flowChange.toFixed(4)}）`
      : `已推进到第 ${data.index} 帧（汇流变化幅度 ${data.flowChange.toFixed(4)}，`
        + `还差 ${Math.max(0, data.flowChange - data.threshold).toFixed(4)}）`
  })
}

async function onJumpToFrame(index) {
  if (!activeChainId.value) return
  await run('evolve', async () => {
    const frame = await api.getEvolutionFrame(activeChainId.value, index)
    heightmap.value = frame.heightmap
    flowmap.value = frame.flow
    viewingFrame.value = frame.index
    stats.value = frame.stats
    evoNotice.value = `正在回看第 ${index} 帧（推进仍会追加到链尾）`
  })
}

function onBranch() {
  if (!activeChainId.value) return
  run('evolve', async () => {
    const result = await api.branchEvolution(activeChainId.value, viewingFrame.value)
    activeChainId.value = result.chain.chainId
    await refreshChainDetail(activeChainId.value)
    heightmap.value = result.frame.heightmap
    flowmap.value = null // 新分支第 0 帧尚未在本链冲刷
    viewingFrame.value = 0
    stats.value = null
    await refreshChains()
    evoNotice.value = `已从第 ${result.chain.branchedFromFrameIndex} 帧岔出新分支 `
      + `${result.chain.chainId.slice(0, 8)}，可换参数走另一种走向`
  })
}

async function onSelectChain(chainId) {
  if (!chainId) return
  await run('evolve', async () => {
    activeChainId.value = chainId
    await refreshChainDetail(chainId)
    const tip = activeChain.value.frameCount - 1
    const frame = await api.getEvolutionFrame(chainId, tip)
    heightmap.value = frame.heightmap
    flowmap.value = frame.flow
    viewingFrame.value = tip
    stats.value = frame.stats
  })
}

function onExportFrame(name) {
  if (!activeChainId.value) return
  run('evolve', async () => {
    await api.exportEvolutionFrame(activeChainId.value, viewingFrame.value, name)
    await refreshSnapshots()
    evoNotice.value = `第 ${viewingFrame.value} 帧已导出为快照「${name}」`
  })
}

async function refreshChainDetail(chainId) {
  activeChain.value = await api.getEvolutionChain(chainId)
}

async function refreshChains() {
  try {
    chains.value = await api.listEvolutionChains()
  } catch {
    // 列表失败不阻塞主流程
  }
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
  refreshChains()
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
}
</style>
