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

// ---- 服务端掌管的侵蚀演进 ----

/** 用一张起始高度场开启一段演进，拿到可一直引用的链标识。 */
export function startEvolution(resolution, heightmap, noise) {
  return post('/evolutions', { resolution, heightmap, noise: noise ?? null })
}

export function listEvolutions() {
  return request('/evolutions')
}

export function getEvolution(chainId) {
  return request('/evolutions/' + encodeURIComponent(chainId))
}

/** 只说一句「再推进一轮，用这组参数」，不回传高度数据。 */
export function advanceEvolution(chainId, erosionParams) {
  return post('/evolutions/' + encodeURIComponent(chainId) + '/advance', { erosion: erosionParams })
}

/** 从链上任意历史帧岔出一条独立分支。 */
export function branchEvolution(chainId, frameIndex) {
  return post('/evolutions/' + encodeURIComponent(chainId) + '/branch', { frameIndex })
}

/** 单独取回某一帧（地形 + 累积汇流场 + 本轮流经场）。 */
export function getEvolutionFrame(chainId, frameIndex) {
  return request(
    '/evolutions/' + encodeURIComponent(chainId) + '/frames/' + frameIndex
  )
}

/** 把任意一帧导出成命名快照留档。 */
export function exportEvolutionFrameSnapshot(chainId, frameIndex, name) {
  return post(
    '/evolutions/' + encodeURIComponent(chainId) + '/frames/' + frameIndex + '/snapshot',
    { name }
  )
}
