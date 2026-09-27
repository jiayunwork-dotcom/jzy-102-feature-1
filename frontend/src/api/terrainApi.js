/**
 * 与后端交互的全部请求封装。后端把参数非法的情况以 400 + {error: 原因}
 * 返回，这里统一把原因抛成 Error 供界面展示。
 */
const BASE = '/api'

async function request(path, options = {}) {
  const res = await fetch(BASE + path, options)
  if (!res.ok) {
    let message = `请求失败（HTTP ${res.status}）`
    try {
      const body = await res.json()
      if (body && body.error) message = body.error
      else if (body && body.message) message = body.message
    } catch {
      // 响应体不是 JSON，保留默认信息
    }
    throw new Error(message)
  }
  return res.json()
}

function post(path, payload) {
  return request(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

/** 按噪声参数生成一张新高度场。 */
export function generateTerrain(noiseParams) {
  return post('/terrain/generate', noiseParams)
}

/** 在当前高度场基础上追加一轮侵蚀。 */
export function erodeTerrain(resolution, heightmap, erosionParams) {
  return post('/terrain/erode', {
    resolution,
    heightmap,
    erosion: erosionParams
  })
}

export function listSnapshots() {
  return request('/snapshots')
}

export function saveSnapshot(payload) {
  return post('/snapshots', payload)
}

export function loadSnapshot(name) {
  return request('/snapshots/' + encodeURIComponent(name))
}

// ---- 侵蚀演进（服务端掌管的时间线，推进时不再回传高度场）----

/** 以一张起始高度场开启一段演进，返回链摘要（含可长期引用的 chainId）。 */
export function startEvolution(resolution, heightmap, noise) {
  return post('/evolution', { resolution, heightmap, noise })
}

/** 对指定链推进一轮侵蚀：只提交侵蚀参数，返回追加的新帧与稳态结论。 */
export function advanceEvolution(chainId, erosionParams) {
  return post(`/evolution/${encodeURIComponent(chainId)}/advance`, { erosion: erosionParams })
}

/** 列出全部演进链（含分支血缘、帧数、稳态）。 */
export function listEvolutionChains() {
  return request('/evolution')
}

/** 查一条链的详情：帧序、每帧参数与汇流场变化幅度、分支血缘。 */
export function getEvolutionChain(chainId) {
  return request(`/evolution/${encodeURIComponent(chainId)}`)
}

/** 单独取回链上的一帧（地形 + 逐轮汇流场 + 参数 + 统计）。 */
export function getEvolutionFrame(chainId, frameIndex) {
  return request(`/evolution/${encodeURIComponent(chainId)}/frames/${frameIndex}`)
}

/** 从链上某历史帧岔出一条独立新分支，返回新链摘要与其第 0 帧。 */
export function branchEvolution(chainId, frameIndex) {
  return post(`/evolution/${encodeURIComponent(chainId)}/branch`, { frameIndex })
}

/** 查该链是否已收敛到河网稳态（结论、当前变化幅度、阈值）。 */
export function getEvolutionSteady(chainId) {
  return request(`/evolution/${encodeURIComponent(chainId)}/steady`)
}

/** 把链上任意一帧导出为命名快照。 */
export function exportEvolutionFrame(chainId, frameIndex, name) {
  return post(
    `/evolution/${encodeURIComponent(chainId)}/frames/${frameIndex}/snapshot`,
    { name }
  )
}
