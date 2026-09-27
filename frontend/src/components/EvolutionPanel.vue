<template>
  <aside class="evo-panel">
    <h1>侵蚀演进时间线</h1>

    <section>
      <h2>开启演进</h2>
      <p class="hint">以当前画面中的地形为第 0 帧，在服务端开一条可推进、可回退、
        可分叉的时间线。之后推进时地图不再往返浏览器。</p>
      <button class="primary" :disabled="busy || !hasTerrain" @click="$emit('start')">
        以当前地形开启演进
      </button>
    </section>

    <section v-if="chains.length">
      <h2>演进链</h2>
      <select :value="activeChainId || ''" @change="$emit('select-chain', $event.target.value)">
        <option value="" disabled>选择一条链…</option>
        <option v-for="c in chains" :key="c.chainId" :value="c.chainId">
          {{ shortId(c.chainId) }}
          （{{ c.frameCount }} 帧{{ c.branchedFromChainId
            ? `，岔自 ${shortId(c.branchedFromChainId)} #${c.branchedFromFrameIndex}` : '' }}）
        </option>
      </select>
    </section>

    <template v-if="activeChain">
      <section>
        <h2>稳态</h2>
        <div class="steady" :class="{ on: isSteady }">
          <span class="badge">{{ isSteady ? '已收敛到河网稳态' : '尚未稳态' }}</span>
          <span v-if="lastChange === null || lastChange === undefined" class="steady-num">
            还未冲刷过
          </span>
          <span v-else class="steady-num">
            变化幅度 {{ formatNum(lastChange) }} / 阈值 {{ formatNum(threshold) }}
            （还差 {{ formatNum(Math.max(0, lastChange - threshold)) }}）
          </span>
        </div>
      </section>

      <section>
        <h2>帧时间线</h2>
        <p class="hint">
          当前查看第 <b>{{ viewingIndex }}</b> 帧 / 链尾第
          <b>{{ activeChain.frameCount - 1 }}</b> 帧；点帧号回看，推进仍追加在链尾。
        </p>
        <div class="frames">
          <button
            v-for="f in activeChain.frames"
            :key="f.index"
            class="frame"
            :class="{ active: f.index === viewingIndex, tip: f.index === activeChain.frameCount - 1 }"
            :title="frameTitle(f)"
            @click="$emit('jump', f.index)"
          >{{ f.index }}</button>
        </div>
        <div v-if="viewedFrame" class="frame-params">
          <span v-if="viewedFrame.index === 0">起始帧（尚未冲刷）</span>
          <span v-else>
            雨滴 {{ viewedFrame.params.dropletCount }} · 侵蚀率
            {{ viewedFrame.params.erodeRate }} · 种子 {{ viewedFrame.params.seed }}
            · 汇流变化 {{ formatNum(viewedFrame.flowChange) }}
          </span>
        </div>
      </section>

      <section>
        <h2>操作</h2>
        <button class="primary advance" :disabled="busy" @click="$emit('advance')">
          {{ busy === 'evolve' ? '冲刷中…' : '再推进一轮（用左侧侵蚀参数）' }}
        </button>
        <p class="hint">
          每轮都用左侧当前的侵蚀参数；参数（含种子）保持不变地连续推进，
          河网才会按上面的变化幅度逐步收敛到稳态。
        </p>
        <button :disabled="busy" @click="$emit('branch')">
          从第 {{ viewingIndex }} 帧岔出分支
        </button>
        <div class="snapshot-save">
          <input v-model.trim="snapshotName" type="text" placeholder="快照名称" maxlength="100" />
          <button :disabled="busy || !snapshotName" @click="emitExport">
            导出第 {{ viewingIndex }} 帧
          </button>
        </div>
        <label class="check">
          <input type="checkbox" :checked="showFlow" @change="$emit('update:showFlow', $event.target.checked)" />
          在地形上叠加汇流场（河网）
        </label>
      </section>
    </template>

    <div v-if="error" class="error-box">{{ error }}</div>
    <div v-if="notice" class="notice-box">{{ notice }}</div>
  </aside>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  hasTerrain: { type: Boolean, default: false },
  busy: { type: [String, Boolean], default: false },
  // GET /api/evolution 返回的链摘要数组
  chains: { type: Array, default: () => [] },
  activeChainId: { type: String, default: null },
  // GET /api/evolution/{id} 返回的链详情（含 frames 元信息）
  activeChain: { type: Object, default: null },
  viewingIndex: { type: Number, default: 0 },
  showFlow: { type: Boolean, default: true },
  error: { type: String, default: '' },
  notice: { type: String, default: '' }
})

const emit = defineEmits([
  'start',
  'advance',
  'jump',
  'branch',
  'select-chain',
  'export-snapshot',
  'update:showFlow'
])

const snapshotName = ref('')

const isSteady = computed(() => Boolean(props.activeChain?.steady))
const lastChange = computed(() => props.activeChain?.lastChange ?? null)
const threshold = computed(() => props.activeChain?.threshold ?? 0)

const viewedFrame = computed(() =>
  props.activeChain?.frames?.find(f => f.index === props.viewingIndex) || null)

function shortId(id) {
  return id ? id.slice(0, 8) : ''
}

function formatNum(v) {
  if (v === null || v === undefined) return '—'
  return Number(v).toFixed(4)
}

function frameTitle(f) {
  if (f.index === 0) return '第 0 帧：起始地形'
  return `第 ${f.index} 帧：雨滴 ${f.params.dropletCount}，种子 ${f.params.seed}，`
    + `总步数 ${f.stats.totalSteps}，汇流变化 ${formatNum(f.flowChange)}`
}

function emitExport() {
  emit('export-snapshot', snapshotName.value)
  snapshotName.value = ''
}
</script>

<style scoped>
.evo-panel {
  width: 320px;
  min-width: 320px;
  height: 100%;
  overflow-y: auto;
  background: #141b26;
  color: #cfd8e6;
  padding: 16px 18px 32px;
  box-sizing: border-box;
  font-size: 13px;
  border-left: 1px solid #232d3d;
}
h1 {
  font-size: 17px;
  margin: 2px 0 14px;
  color: #eef3fa;
}
h2 {
  font-size: 13px;
  margin: 0 0 10px;
  color: #8fa3c0;
  letter-spacing: 0.05em;
}
section {
  border-top: 1px solid #232d3d;
  padding: 14px 0;
}
.hint {
  margin: 0 0 9px;
  color: #8294ad;
  line-height: 1.5;
}
b {
  color: #e8eef7;
}
button {
  border: 1px solid #33405a;
  background: #232e40;
  color: #d5e0f0;
  border-radius: 6px;
  padding: 7px 12px;
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
  width: 100%;
  margin-top: 2px;
  background: #2f5f9e;
  border-color: #3a72bd;
  color: #fff;
  font-weight: 600;
}
button.primary.advance {
  background: #2f7a57;
  border-color: #3a9468;
}
select {
  width: 100%;
  background: #10161f;
  border: 1px solid #33405a;
  border-radius: 6px;
  color: #dfe7f2;
  padding: 7px 8px;
  box-sizing: border-box;
}
.steady {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 9px 11px;
  border-radius: 6px;
  background: #2b2418;
  border: 1px solid #4a3d22;
}
.steady.on {
  background: #18301f;
  border-color: #2e5d39;
}
.badge {
  font-weight: 600;
  color: #e7c98a;
}
.steady.on .badge {
  color: #8fe0a3;
}
.steady-num {
  color: #a9b8cd;
  font-size: 12px;
}
.frames {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  max-height: 120px;
  overflow-y: auto;
}
.frame {
  min-width: 34px;
  padding: 5px 0;
  font-variant-numeric: tabular-nums;
}
.frame.active {
  background: #3a72bd;
  border-color: #5a92dd;
  color: #fff;
  font-weight: 600;
}
.frame.tip {
  outline: 1px dashed #6d7f9a;
  outline-offset: 1px;
}
.frame.tip.active {
  outline-color: #9fc0ee;
}
.frame-params {
  margin-top: 8px;
  color: #8fa3c0;
  font-size: 12px;
  line-height: 1.5;
}
.snapshot-save {
  display: flex;
  gap: 6px;
  margin-top: 8px;
}
.snapshot-save input {
  flex: 1;
  min-width: 0;
  background: #10161f;
  border: 1px solid #33405a;
  border-radius: 6px;
  color: #dfe7f2;
  padding: 6px 8px;
}
.check {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 10px;
  color: #a9b8cd;
  cursor: pointer;
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
