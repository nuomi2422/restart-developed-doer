package com.dwinovo.numen.worldstate.core.observation.payload;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.ObservationPayload;

/**
 * 有界实体计数。第二版真实采集器——直接服务已建成的羊圈/牛圈，验证架构不是空架子。
 *
 * <p><b>零 / 部分 / 未加载必须能区分</b>：这里只记本次观察到的数量与扫描覆盖，
 * 结论（"确认没有"还是"只是没看全"）由外层的 {@code Coverage} 定夺，不在这里下判断。
 *
 * @param dimension     维度 id
 * @param box           查询范围
 * @param entityType    过滤的实体类型，如 {@code minecraft:sheep}；为空表示按 {@code category}
 * @param category      {@code passive}（动物）/ {@code all}
 * @param count         本次观察到的实体数量（下限，不是总数，除非 Coverage=COMPLETE）
 * @param scannedColumns 已扫的列数
 * @param skippedColumns 因区块未加载而跳过的列数
 */
public record EntityCountPayload(String dimension, Bounds box, String entityType, String category,
                                 int count, int scannedColumns, int skippedColumns)
        implements ObservationPayload {

    @Override
    public Topic topic() {
        return Topic.ENTITIES;
    }

    /** 过滤条件的规范串（与 {@code ObservationRequest.filter} 同口径）。 */
    public String filterId() {
        return entityType != null && !entityType.isBlank()
                ? "type=" + entityType : "category=" + (category == null ? "all" : category);
    }
}
