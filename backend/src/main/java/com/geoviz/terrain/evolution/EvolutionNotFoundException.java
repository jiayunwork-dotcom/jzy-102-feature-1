package com.geoviz.terrain.evolution;

/**
 * 引用了不存在的演进链或帧（链 id 未知，或帧序号不在该链范围内）。
 *
 * <p>这类错误是「引用的资源不存在」，接口层映射为 404，与参数本身非法（400）区分。
 */
public class EvolutionNotFoundException extends RuntimeException {

    public EvolutionNotFoundException(String message) {
        super(message);
    }
}
