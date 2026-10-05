package com.industrial.erp.modules.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.json.JSONObject;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.ai.agent.AgentService;
import com.industrial.erp.modules.ai.vo.AgentResultVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * v1.1.75 任务2 — AI agent 端点 (只读查询 + 写操作"提议/确认"闭环).
 *
 * <p>{@code POST /ai/agent/chat}: 自由问答, agent 自主调查询工具; 若涉及写操作,
 * 返回 {@code proposedActions} 供前端弹确认 (用户点确认后调真实写端点)。
 * 权限: 复用 {@code report:view} (超管 orRole 短路)。
 */
@Tag(name = "AI Agent")
@RestController
@RequestMapping("/ai/agent")
public class AiAgentController {

    private final AgentService agentService;

    public AiAgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 请求体: {"question": "查一下透明胶带现在库存多少"} */
    @SaCheckPermission(value = {"report:view"}, orRole = "admin")
    @PostMapping("/chat")
    public R<AgentResultVO> chat(@RequestBody JSONObject body) {
        String question = body == null ? "" : body.getStr("question", "");
        AgentService.Result r = agentService.run(question == null ? "" : question);
        return R.ok(new AgentResultVO(r.answer, r.proposedActions, r.stepsUsed, r.model));
    }
}
