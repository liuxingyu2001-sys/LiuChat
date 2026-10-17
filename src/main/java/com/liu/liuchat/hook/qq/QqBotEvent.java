package com.liu.liuchat.hook.qq;

/**
 * QQ 官方机器人收到的一条消息（已归一化为游戏侧可用的最小信息）。
 *
 * @param eventId  平台事件 ID（被动回复时作为 msg_id）
 * @param type     会话类型
 * @param targetId 群 openid / 用户 openid / 频道 ID
 * @param senderId 发送者 openid 或用户 ID
 * @param senderName 平台提供的昵称；群聊/私聊通常为空，由桥接层生成短名
 * @param content  已去掉 @ 标签的正文
 */
public record QqBotEvent(String eventId, QqTarget.Type type, String targetId,
                         String senderId, String senderName, String content) {

    public QqTarget target() {
        return new QqTarget(type, targetId);
    }
}
