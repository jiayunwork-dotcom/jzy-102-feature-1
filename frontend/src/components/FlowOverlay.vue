<template>
  <!--
    纯 Three.js 叠加层：不占任何 DOM，作为 TerrainViewer 的插槽子节点注入
    其场景，自行创建/销毁一张贴在地形表面上的半透明河网网格。
  -->
</template>

<script setup>
import { onBeforeUnmount, watch } from 'vue'
import * as THREE from 'three'
import { useTerrainScene } from './terrainContext.js'

const props = defineProps({
  // 长度为 resolution^2 的高度数组（行优先），用于把河网贴合在地表
  heightmap: { type: Array, default: null },
  // 该帧的逐轮汇流场（格点流经次数），null 表示不叠加
  flowmap: { type: Array, default: null },
  resolution: { type: Number, default: 0 }
})

const ctx = useTerrainScene()
let overlay = null

// 干流：深河蓝；水大处向青白提亮。对数归一化避免少数大值把小河全压没
const RIVER_DEEP = new THREE.Color('#1f6fd0')
const RIVER_FOAM = new THREE.Color('#cfeaff')
const tmpColor = new THREE.Color()

function rebuild() {
  disposeOverlay()
  if (!ctx) return
  const scene = ctx.getScene()
  const h = props.heightmap
  const flow = props.flowmap
  const res = props.resolution
  if (!scene || !h || !flow || !res) return

  let min = Infinity
  let max = -Infinity
  let maxFlow = 0
  for (let i = 0; i < h.length; i++) {
    if (h[i] < min) min = h[i]
    if (h[i] > max) max = h[i]
    if (flow[i] > maxFlow) maxFlow = flow[i]
  }
  if (maxFlow <= 0) return
  const range = Math.max(max - min, 1e-9)
  const logMax = Math.log1p(maxFlow)
  const W = ctx.worldSize
  const H = ctx.heightScale

  const geometry = new THREE.PlaneGeometry(W, W, res - 1, res - 1)
  geometry.rotateX(-Math.PI / 2)
  const pos = geometry.attributes.position
  // 与 TerrainViewer 相同的顶点排布与高度归一化，再整体抬一点点防止深度闪烁
  for (let i = 0; i < pos.count; i++) {
    pos.setY(i, ((h[i] - min) / range) * H + 0.18)
  }
  // 逐顶点 RGBA（itemSize=4）：three 会据此启用顶点透明度
  const colors = new Float32Array(pos.count * 4)
  for (let i = 0; i < pos.count; i++) {
    const t = Math.log1p(flow[i]) / logMax // 0..1
    // 低于一点底噪不显示，主干基本不透明
    const a = THREE.MathUtils.smoothstep(t, 0.12, 0.65) * 0.92
    tmpColor.copy(RIVER_DEEP).lerp(RIVER_FOAM, THREE.MathUtils.smoothstep(t, 0.55, 1.0))
    colors[i * 4] = tmpColor.r
    colors[i * 4 + 1] = tmpColor.g
    colors[i * 4 + 2] = tmpColor.b
    colors[i * 4 + 3] = a
  }
  geometry.setAttribute('color', new THREE.BufferAttribute(colors, 4))

  const material = new THREE.MeshBasicMaterial({
    vertexColors: true,
    transparent: true,
    depthWrite: false
  })
  overlay = new THREE.Mesh(geometry, material)
  overlay.renderOrder = 2
  scene.add(overlay)
}

function disposeOverlay() {
  if (!overlay || !ctx) return
  ctx.getScene().remove(overlay)
  overlay.geometry.dispose()
  overlay.material.dispose()
  overlay = null
}

watch(() => [props.flowmap, props.heightmap, props.resolution], rebuild, { immediate: true })

onBeforeUnmount(disposeOverlay)
</script>
