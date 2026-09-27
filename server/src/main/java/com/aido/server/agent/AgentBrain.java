package com.aido.server.agent;

import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/**
 * 小雀的「大脑」：根据用户的话和主窗口上下文，给出思考步骤和要追加到对话流的卡片。
 * 现在是规则实现（{@link RuleBasedAgentBrain}），接入大模型时实现这个接口替换即可，接口和前端都不用改。
 */
public interface AgentBrain {

  Reply reply(String me, String text, Context context);

  /**
   * 主窗口此刻在看什么，作为对话上下文。
   *
   * @param view           chat / todo / memory / search
   * @param label          展示用，如「消息 · 周远」
   * @param conversationId view 为 chat 时的会话
   */
  record Context(String view, String label, String conversationId) {}

  /** @param steps 思考步骤，前端逐条播放 */
  record Reply(List<String> steps, List<Card> cards) {}

  /** 对话流卡片：type 见 agent_feed_item 表注释，payload 为卡片内容。 */
  record Card(String type, ObjectNode payload) {}
}
