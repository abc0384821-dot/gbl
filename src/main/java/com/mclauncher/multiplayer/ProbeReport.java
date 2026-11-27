package com.mclauncher.multiplayer;

import java.util.List;

/**
 * 一次联网体检的完整结论（架构规格 §2.3 的单列 record）。
 *
 * <p>字段类型复用 {@link NetworkProbe} 里的枚举；notes 为不可变列表。
 * 任何一项检测失败都保持 UNKNOWN / 空 notes，不抛、不阻塞、不做门禁。
 */
public record ProbeReport(NetworkProbe.PublicIpv4 ipv4,
                          NetworkProbe.PublicIpv6 ipv6,
                          NetworkProbe.Cgnat cgnat,
                          NetworkProbe.NatTier natTier,
                          NetworkProbe.Relay relay,
                          List<String> notes) {

    public ProbeReport {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
