<template>
  <aside class="evo-panel">
    <h2>侵蚀演进（服务端时间线）</h2>

    <div class="row">
      <button class="primary small" :disabled="busy || !hasTerrain" @click="emit('start')">
        用当前地形开启演进
      </button>
    </div>

    <select v-if="chains.length" v-model="selectedChainId" class="chain-select"
            @change="onSelectChain">
      <option v-for="c in chains" :key="c.id" :value="c.id">
        {{ describe(c) }}
      </option>
    </select>

    <template v-if="chain">
      <p class="lineage" v-if="chain.branch">
        分支：来自链 {{ short(chain.parentChainId) }} 的第 {{ chain.parentFrameIndex }} 帧
      </p>
      <p class="lineage" v-else>根链（非分支）</p>

      <div class="status">
        <div class="status-head">
          <span>已走 <b>{{ chain.frameCount }}</b> 帧 · 当前 #{{ chain.headIndex }}</span>
          <span :class="['badge', converged ? 'ok' : 'pending']">
            {{ converged ? '已稳态' : '未稳态' }}
          </span>
        </div>
        <div class="progress-wrap">
          <div class="progress-track">
            <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
            <div class="progress-needle" :style="{ left: thresholdPercent + '%' }"
                 :title="'阈值 ' + chain.convergenceThreshold"></div>
          </div>
        </div>
        <p class="converge-detail">
          当前变化幅度 <b>{{ format(currentFlowChange) }}</b>
          · 阈值 {{ chain.convergenceThreshold }}
          · {{ convergeHint }}
        </p>
      </div>

      <button class="primary small" :disabled="busy" @click="emit('advance')">
        {{ busy === 'evolve' ? '冲刷中…' : '再冲刷一轮（用左侧侵蚀参数）' }}
      </button>

      <h3>帧序列（点击跳回查看）</h3>
      <div class="frames">
        <button v-for="f in chain.frames" :key="f.index"
                :class="['frame-chip', { active: f.index === viewingFrameIndex }]"
                :title="frameTitle(f)"
                @click="emit('view-frame', f.index)">
          {{ f.index }}
        </button>
      </div>

      <div class="actions">
        <button class="small-btn" :disabled="busy" @click="emit('branch', viewingFrameIndex)">
          从第 {{ viewingFrameIndex }} 帧岔出新分支
        </button>
        <button class="small-btn" :disabled="busy" @click="exportSnapshot">
          把第 {{ viewingFrameIndex }} 帧存为命名快照
        </button>
      </div>

      <label class="toggle">
        <input type="checkbox" v-model="showFlowLocal"
               @change="emit('update:showFlow', showFlowLocal)" />
        叠加显示汇流河网
      </label>
      <p class="flow-note">
        累积汇流场：演进以来雨滴沿坡汇流的平均分布，干流更亮；
        本轮流经量总和 = 当轮雨滴步数之和。
      </p>
    </template>

    <p v-else class="empty">还没有演进链。生成地形后点「开启演进」。</p>

    <div v-if="error" class="error-box">{{ error }}</div>
    <div v-if="notice" class="notice-box">{{ notice }}</div>
  </aside>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  chains: { type: Array, default: () => [] },
  chain: { type: Object, default: null },
  selectedChainId: { type: String, default: '' },
  viewingFrameIndex: { type: Number, default: 0 },
  hasTerrain: { type: Boolean, default: false },
  busy: { type: [String, Boolean], default: false },
  error: { type: String, default: '' },
  notice: { type: String, default: '' },
  showFlow: { type: Boolean, default: true }
})

const emit = defineEmits([
  'start',
  'advance',
  'branch',
  'view-frame',
  'select-chain',
  'export-snapshot',
  'update:showFlow'
])

const showFlowLocal = ref(props.showFlow)
watch(() => props.showFlow, v => { showFlowLocal.value = v })

const selectedChainId = ref(props.selectedChainId)
watch(() => props.selectedChainId, v => { selectedChainId.value = v })

const currentFlowChange = computed(() => props.chain?.currentFlowChange ?? null)
const converged = computed(() => Boolean(props.chain?.converged))
const threshold = computed(() => props.chain?.convergenceThreshold ?? 0.03)

// 变化幅度的展示区间用一个略大于阈值的量程（0 → viewMax），让阈值线和进度直观
const viewMax = computed(() => Math.max(0.3, threshold.value * 6))
const progressPercent = computed(() => {
  if (currentFlowChange.value == null) return 100
  const pct = 100 * (1 - Math.min(1, currentFlowChange.value / viewMax.value))
  return Math.max(0, Math.min(100, pct))
})
const thresholdPercent = computed(
  () => 100 * (1 - Math.min(1, threshold.value / viewMax.value))
)
const convergeHint = computed(() => {
  if (currentFlowChange.value == null) return '推进第一轮后开始度量'
  if (converged.value) return '河网已趋于稳态'
  return `再收窄 ${(currentFlowChange.value - threshold.value).toFixed(4)} 即到阈值`
})

function format(v) {
  return v == null ? '—' : Number(v).toFixed(4)
}

function short(id) {
  return id ? id.slice(0, 8) : ''
}

function describe(c) {
  const base = `${short(c.id)} · ${c.frameCount}帧`
  return c.branch ? `⎇ ${base}（从#${c.parentFrameIndex}）` : base
}

function frameTitle(f) {
  if (f.index === 0) return '第 0 帧（起始地形）'
  const steps = f.stats?.totalSteps ?? '?'
  const change = f.flowChange == null ? '—' : f.flowChange.toFixed(4)
  return `第 ${f.index} 帧 · 雨滴步数 ${steps} · 汇流变化 ${change} · 种子 ${f.erosion?.seed ?? '—'}`
}

function onSelectChain() {
  emit('select-chain', selectedChainId.value)
}

function exportSnapshot() {
  const name = window.prompt('把第 ' + props.viewingFrameIndex + ' 帧另存为快照，命名：')
  if (name && name.trim()) {
    emit('export-snapshot', { frameIndex: props.viewingFrameIndex, name: name.trim() })
  }
}
</script>

<style scoped>
.evo-panel {
  width: 300px;
  min-width: 300px;
  height: 100%;
  overflow-y: auto;
  background: #141b26;
  color: #cfd8e6;
  padding: 16px 16px 32px;
  box-sizing: border-box;
  font-size: 13px;
  border-left: 1px solid #232d3d;
}
h2 {
  font-size: 14px;
  margin: 0 0 12px;
  color: #eef3fa;
}
h3 {
  font-size: 12px;
  margin: 16px 0 8px;
  color: #8fa3c0;
}
.row {
  margin-bottom: 10px;
}
button {
  border: 1px solid #33405a;
  background: #232e40;
  color: #d5e0f0;
  border-radius: 6px;
  padding: 7px 10px;
  cursor: pointer;
  font-size: 13px;
}
button:hover:not(:disabled) {
  background: #2d3b53;
}
button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
button.primary {
  background: #2f5f9e;
  border-color: #3a72bd;
  color: #fff;
  font-weight: 600;
}
button.primary.small {
  width: 100%;
}
.chain-select {
  width: 100%;
  margin-bottom: 10px;
  background: #10161f;
  border: 1px solid #33405a;
  border-radius: 6px;
  color: #dfe7f2;
  padding: 6px 8px;
}
.lineage {
  color: #7f93b0;
  font-size: 12px;
  margin: 6px 0;
}
.status {
  background: #10161f;
  border: 1px solid #243044;
  border-radius: 8px;
  padding: 10px;
  margin: 10px 0;
}
.status-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
}
.badge.ok {
  background: #1f4a2f;
  color: #9fe0b0;
}
.badge.pending {
  background: #4a3c1f;
  color: #e6cf8f;
}
.progress-track {
  position: relative;
  height: 8px;
  border-radius: 4px;
  background: linear-gradient(90deg, #5a2b33, #caa14a 40%, #2f6e45);
}
.progress-fill {
  position: absolute;
  top: 0;
  left: 0;
  height: 100%;
  background: rgba(20, 27, 38, 0.55);
  border-radius: 4px;
  transition: width 0.25s ease;
}
.progress-needle {
  position: absolute;
  top: -3px;
  width: 2px;
  height: 14px;
  background: #eef3fa;
}
.converge-detail {
  margin: 8px 0 0;
  font-size: 12px;
  color: #a9b8cd;
}
.frames {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.frame-chip {
  min-width: 30px;
  padding: 5px 0;
  border-radius: 5px;
  font-variant-numeric: tabular-nums;
}
.frame-chip.active {
  background: #3a72bd;
  border-color: #4f8fdd;
  color: #fff;
}
.actions {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-top: 12px;
}
.small-btn {
  font-size: 12px;
  padding: 6px 8px;
}
.toggle {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 14px;
}
.flow-note {
  color: #6d7f9a;
  font-size: 11px;
  line-height: 1.5;
  margin: 8px 0 0;
}
.empty {
  color: #5c6b82;
  margin-top: 12px;
}
.error-box {
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #4a2027;
  border: 1px solid #7a3540;
  color: #f0b9c0;
  word-break: break-all;
}
.notice-box {
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #1f3a2a;
  border: 1px solid #35603f;
  color: #a9dcb4;
}
</style>
