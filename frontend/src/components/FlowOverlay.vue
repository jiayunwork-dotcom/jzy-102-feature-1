<template>
  <!-- 纯 Three.js 叠加组件：不画自己的 DOM（图例在演进面板里）。
       它把累积汇流场渲染成贴着地表的发光点层，河网越粗（汇流越强）越亮。 -->
  <div class="flow-overlay-mount" aria-hidden="true"></div>
</template>

<script setup>
import { onBeforeUnmount, watch } from 'vue'
import * as THREE from 'three'
import { WORLD_SIZE, cellWorldPosition, heightRange } from '../terrainGeometry.js'

const props = defineProps({
  // TerrainViewer 通过 ready 事件提供的三维上下文（场景、相机）
  viewer: { type: Object, default: null },
  flow: { type: Array, default: null },       // 归一化累积汇流场
  heightmap: { type: Array, default: null },
  resolution: { type: Number, default: 0 },
  visible: { type: Boolean, default: true }
})

let points = null

/** 只显示汇流强度达到最大值一定比例以上的格点，避免零散雨滴噪声盖住地形。 */
const DISPLAY_THRESHOLD = 0.12

function disposePoints() {
  if (!points || !props.viewer) return
  props.viewer.scene.remove(points)
  points.geometry.dispose()
  points.material.dispose()
  points = null
}

function rebuild() {
  disposePoints()
  const { scene } = props.viewer || {}
  if (!scene || !props.visible || !props.flow || !props.heightmap || !props.resolution) return

  const res = props.resolution
  const { min, range } = heightRange(props.heightmap)

  let maxFlow = 0
  for (const v of props.flow) if (v > maxFlow) maxFlow = v
  if (maxFlow <= 0) return

  const positions = []
  const colors = []
  const color = new THREE.Color()

  for (let y = 0; y < res; y++) {
    for (let x = 0; x < res; x++) {
      const idx = y * res + x
      const v = props.flow[idx]
      if (v < maxFlow * DISPLAY_THRESHOLD) continue

      // 对数强度：让细支沟与干流都能分辨，同时干流明显更亮
      const intensity = Math.log1p((v / maxFlow) * 9) / Math.log1p(9) // (0,1]
      const [wx, wy, wz] = cellWorldPosition(
        x,
        y,
        props.heightmap[idx],
        res,
        min,
        range
      )
      positions.push(wx, wy + 0.35, wz)

      // 浅青（细支沟）→ 饱和蓝（干流）
      color.setHSL(0.58 - 0.12 * intensity, 0.95, 0.35 + 0.5 * intensity)
      colors.push(color.r, color.g, color.b)
    }
  }

  if (!positions.length) return

  const geometry = new THREE.BufferGeometry()
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3))
  geometry.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3))

  const material = new THREE.PointsMaterial({
    size: (WORLD_SIZE / res) * 1.7,
    sizeAttenuation: true,
    vertexColors: true,
    transparent: true,
    opacity: 0.9,
    depthWrite: false,
    blending: THREE.AdditiveBlending
  })

  points = new THREE.Points(geometry, material)
  points.renderOrder = 10
  scene.add(points)
}

watch(
  () => [props.viewer, props.flow, props.heightmap, props.resolution, props.visible],
  rebuild,
  { deep: false }
)

onBeforeUnmount(disposePoints)
</script>

<style scoped>
.flow-overlay-mount {
  display: none;
}
</style>
