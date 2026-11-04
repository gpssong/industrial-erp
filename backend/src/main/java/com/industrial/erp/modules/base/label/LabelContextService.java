package com.industrial.erp.modules.base.label;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.label.context.LabelContextLoader;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.1.167 按 bizType 找 loader 取业务字段，再把标签主数据并进每一项 (docs/32 §2.1 / §4)。
 *
 * <p>Spring 注入全部 LabelContextLoader 实现 ⇒ 新增一类标签只需加一个 @Component，这里零改动。
 */
@Service
public class LabelContextService {

    public LabelContextService(List<LabelContextLoader> loaders, LabelMasterService masterService) {
        this.loaders = new HashMap<>();
        for (LabelContextLoader l : loaders) this.loaders.put(l.bizType(), l);
        this.masterService = masterService;
    }

    private final Map<LabelBizType, LabelContextLoader> loaders;
    private final LabelMasterService masterService;

    public List<Map<String, Object>> load(String bizTypeCode, Long bizId, List<Long> lineIds) {
        LabelBizType type = LabelBizType.of(bizTypeCode);
        LabelContextLoader loader = loaders.get(type);
        if (loader == null) throw BizException.of(400, "该标签类型还没有数据加载器: " + type.getCode());
        List<Map<String, Object>> rows = loader.load(bizId, lineIds);
        LabelMaster master = masterService.load();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new HashMap<>(row);
            m.put("producerName", master.getProducerName());
            m.put("producerAddress", master.getProducerAddress());
            m.put("xkNo", master.getXkNo());
            m.put("execStandard", master.getExecStandard());
            m.put("storageCondition", master.getStorageCondition());
            m.put("inspectorCode", master.getInspectorCode());
            out.add(m);
        }
        return out;
    }

    /** 单号归一 / 单张预览用：只取第一项（一张单据只有一个单号，不必展开全部明细行）。 */
    public Map<String, Object> loadFirst(String bizTypeCode, Long bizId, List<Long> lineIds) {
        List<Map<String, Object>> rows = load(bizTypeCode, bizId, lineIds);
        if (rows.isEmpty()) throw BizException.of(404, "该单据没有可打印的内容: " + bizTypeCode + " id=" + bizId);
        return rows.get(0);
    }
}
