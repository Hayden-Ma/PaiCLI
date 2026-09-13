package com.paicli.eval.benchmark;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 评测运行的静默渲染器：吃掉 ReAct 循环的全部 UI 回调，
 * 保证无头 bench 子命令的 stdout 只输出评测自己的进度与报告。
 * HITL 审批请求一律拒绝（评测 worker 无人值守，高危操作不应被静默批准）。
 */
public final class SilentRenderer implements Renderer {
    @Override
    public void start() {
    }

    @Override
    public PrintStream stream() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
    }

    @Override
    public void updateStatus(StatusInfo status) {
    }

    @Override
    public int openPalette(String title, List<String> items) {
        return -1;
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        return ApprovalResult.reject("评测 worker 无人值守，拒绝高危操作");
    }
}
