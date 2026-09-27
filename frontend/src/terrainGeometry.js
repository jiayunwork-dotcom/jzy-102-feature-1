/**
 * 地形网格的共享几何约定：三维渲染器（TerrainViewer）与汇流场叠加层
 * （FlowOverlay）都用同一组常量和同一套高度→世界坐标映射，
 * 保证叠加的河网与地形格点严格对齐。
 */
export const WORLD_SIZE = 100
export const HEIGHT_SCALE = 26

/** 高度场的最小/最大值与跨度（地形按 min-max 归一化到 [0, HEIGHT_SCALE]）。 */
export function heightRange(heightmap) {
  let min = Infinity
  let max = -Infinity
  for (const v of heightmap) {
    if (v < min) min = v
    if (v > max) max = v
  }
  return { min, max, range: Math.max(max - min, 1e-9) }
}

/** 某个格点 (x, y) 的地形表面世界坐标。 */
export function cellWorldPosition(x, y, h, resolution, min, range) {
  const worldX = -WORLD_SIZE / 2 + (x * WORLD_SIZE) / (resolution - 1)
  const worldZ = -WORLD_SIZE / 2 + (y * WORLD_SIZE) / (resolution - 1)
  const worldY = ((h - min) / range) * HEIGHT_SCALE
  return [worldX, worldY, worldZ]
}
