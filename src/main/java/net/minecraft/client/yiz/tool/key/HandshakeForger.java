package net.minecraft.client.yiz.tool.key;

import java.util.List;

/**
 * ThreadLocal 会话握手伪造器 —— KeyHunter 四步配方的第 4 步（握手侧）。
 *
 * <p>目标「内部 key 判定」的支柱③：特权路径先在 {@code ThreadLocal} 里放令牌，
 * 判定时比对令牌与密钥字段。我们在夺取密钥（第 3 步）后，对普查到的每个握手点
 * {@code set(密钥)} → 执行 → 恢复原值。特权语义完全复刻，判定方法原样未动。</p>
 */
public final class HandshakeForger {

    private final List<StaticFieldCensus.HandshakeField> handshakes;
    private final Object forgedKey;

    public HandshakeForger(List<StaticFieldCensus.HandshakeField> handshakes, Object forgedKey) {
        this.handshakes = handshakes != null ? handshakes : List.of();
        this.forgedKey = forgedKey;
    }

    public boolean hasHandshakes() {
        return !handshakes.isEmpty();
    }

    public boolean hasKey() {
        return forgedKey != null;
    }

    /** 在伪造的会话令牌下执行 r，结束后恢复各握手点原值。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void forge(Runnable r) {
        if (r == null) return;
        Object[] saved = new Object[handshakes.size()];
        for (int i = 0; i < handshakes.size(); i++) {
            ThreadLocal raw = (ThreadLocal) handshakes.get(i).threadLocal();
            saved[i] = raw.get();
            raw.set(forgedKey);
        }
        try {
            r.run();
        } finally {
            for (int i = 0; i < handshakes.size(); i++) {
                ThreadLocal raw = (ThreadLocal) handshakes.get(i).threadLocal();
                if (saved[i] != null) raw.set(saved[i]);
                else raw.remove();
            }
        }
    }
}
