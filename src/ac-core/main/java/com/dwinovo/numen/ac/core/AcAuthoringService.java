package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.api.AcVersionStore;
import com.dwinovo.numen.ac.api.ToolRegistry;
import com.dwinovo.numen.ac.api.ToolSchema;

import java.io.StringReader;
import java.util.List;
import java.util.Optional;

/**
 * Provider-neutral 的 AC authoring / validation / version 服务。接收 AI 生成的
 * AC 定义（对象或 JSON），校验后原子发布；非法候选不破坏当前版本，不引入 LLM 依赖。
 *
 * <p>限制：步骤数上限、每步参数数量上限；工具必须已注册且通过
 * {@link AcParamValidator} 的 schema 校验。
 */
public final class AcAuthoringService {

    /** 单 AC 最大步骤数。 */
    public static final int MAX_STEPS = 50;
    /** 单步最大参数数量。 */
    public static final int MAX_PARAMS_PER_STEP = 32;

    private final ToolRegistry registry;
    private final AcVersionStore store;

    public AcAuthoringService(ToolRegistry registry, AcVersionStore store) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    /** 校验结果。 */
    public record ValidationResult(boolean valid, String reason) {
        public static final ValidationResult OK = new ValidationResult(true, null);
        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /** 校验一个 AC 定义：工具存在 + 参数 schema + 数量/大小限制。 */
    public ValidationResult validate(AcDefinition ac) {
        if (ac == null) return ValidationResult.fail("AC 不能为 null");
        if (ac.steps().size() > MAX_STEPS) {
            return ValidationResult.fail("步骤数超过上限 " + MAX_STEPS + ": " + ac.steps().size());
        }
        for (AcDefinition.AcStep step : ac.steps()) {
            if (!registry.contains(step.tool())) {
                return ValidationResult.fail("工具未注册: " + step.tool());
            }
            if (step.parameters().size() > MAX_PARAMS_PER_STEP) {
                return ValidationResult.fail("步骤 " + step.id() + " 参数超过上限 " + MAX_PARAMS_PER_STEP);
            }
            ToolSchema schema = registry.schema(step.tool()).orElse(null);
            String paramErr = AcParamValidator.validate(schema, step.parameters());
            if (paramErr != null) {
                return ValidationResult.fail("步骤 " + step.id() + ": " + paramErr);
            }
        }
        return ValidationResult.OK;
    }

    /** 解析并校验 AI 输出的 AC JSON。 */
    public ValidationResult validateJson(String json) {
        if (json == null || json.isBlank()) return ValidationResult.fail("AC JSON 为空");
        try {
            return validate(AcJson.load(new StringReader(json)));
        } catch (IllegalArgumentException e) {
            return ValidationResult.fail(e.getMessage());
        }
    }

    /**
     * 校验通过的候选才原子写入版本仓储；失败不写入、不破坏当前版本。
     *
     * @return 已发布的 AC 定义（= candidate）
     * @throws IllegalArgumentException 校验失败时
     */
    public AcDefinition publish(AcDefinition candidate) {
        ValidationResult v = validate(candidate);
        if (!v.valid()) throw new IllegalArgumentException("AC 校验失败: " + v.reason());
        store.put(candidate.name(), candidate.version(), candidate);
        return candidate;
    }

    /** 解析、校验并发布 AI 输出的 AC JSON。 */
    public AcDefinition publishJson(String json) {
        ValidationResult v = validateJson(json);
        if (!v.valid()) throw new IllegalArgumentException("AC JSON 校验失败: " + v.reason());
        AcDefinition ac = AcJson.load(new StringReader(json));
        store.put(ac.name(), ac.version(), ac);
        return ac;
    }

    /** 该 AC 的全部版本号。 */
    public List<String> listVersions(String name) {
        return store.versions(name);
    }

    /** 按名+版本取 AC。 */
    public Optional<AcDefinition> load(String name, String version) {
        return store.get(name, version);
    }

    /** 取 AC 的最高版本。 */
    public Optional<AcDefinition> loadLatest(String name) {
        return store.latest(name);
    }

    /** 底层仓储（供 execute / resume 读取已发布版本）。 */
    public AcVersionStore store() {
        return store;
    }
}
