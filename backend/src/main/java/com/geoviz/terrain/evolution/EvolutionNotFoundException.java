package com.geoviz.terrain.evolution;

/**
 * 引用不存在的演进链或链上不存在的帧时抛出，由全局异常处理器转成
 * 带原因的 404 响应（与非法参数的 400 区分开，但都在动手计算之前拦截）。
 */
public class EvolutionNotFoundException extends RuntimeException {

    public EvolutionNotFoundException(String message) {
        super(message);
    }
}
