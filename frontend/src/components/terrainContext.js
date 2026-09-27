/**
 * TerrainViewer 向其子组件（汇流场叠加层等）暴露 Three.js 场景的注入键。
 * 叠加组件作为 TerrainViewer 的插槽子节点注入访问器，自行创建/销毁自己的
 * 网格，不改动地形渲染文件里的渲染逻辑。
 */
import { inject } from 'vue'

export const TerrainSceneKey = Symbol('terrain-scene')

export function useTerrainScene() {
  return inject(TerrainSceneKey, null)
}
