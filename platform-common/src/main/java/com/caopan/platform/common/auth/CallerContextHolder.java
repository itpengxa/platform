package com.caopan.platform.common.auth;

/**
 * {@link CallerContext} 请求线程绑定（platform-common）。
 * <p>切面在 {@code proceed} 前 {@link #set}，在 {@code finally} 中必须 {@link #clear}，
 * 避免虚拟线程/线程池复用导致上下文串号。</p>
 */
public final class CallerContextHolder {

    private static final ThreadLocal<CallerContext> HOLDER = new ThreadLocal<>();

    private CallerContextHolder() {
    }

    /**
     * 绑定当前请求的调用方。
     *
     * @param context 调用方上下文，可为 null（等价于清除意图，仍建议显式 clear）
     */
    public static void set(CallerContext context) {
        HOLDER.set(context);
    }

    /**
     * @return 当前线程绑定的调用方，未设置时为 null
     */
    public static CallerContext get() {
        return HOLDER.get();
    }

    /**
     * 清除当前线程绑定，防止泄漏到后续请求。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
