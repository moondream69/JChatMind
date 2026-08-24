import React from "react";
import { Alert, Tag } from "antd";
import { MessageOutlined } from "@ant-design/icons";
import { getAgentEmoji } from "../../../utils";
import type { AgentVO } from "../../../api/api.ts";

interface ChatHeaderProps {
  agent?: AgentVO;
  /** 会话标题（未设置时留空） */
  sessionTitle?: string;
  messageCount: number;
  sseDisconnected: boolean;
}

/** 聊天界面顶部信息条：智能体、模型、标题、消息统计与连接状态 */
const ChatHeader: React.FC<ChatHeaderProps> = ({
  agent,
  sessionTitle,
  messageCount,
  sseDisconnected,
}) => {
  return (
    <div className="border-b border-gray-200 bg-white">
      <div className="px-4 py-3 flex items-center gap-3">
        <div className="w-9 h-9 rounded-full bg-gradient-to-br from-blue-100 to-purple-100 flex items-center justify-center text-lg shrink-0">
          {agent ? (
            getAgentEmoji(agent.id)
          ) : (
            <MessageOutlined className="text-blue-500" />
          )}
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-semibold text-gray-900 truncate">
              {agent?.name ?? "聊天会话"}
            </span>
            {agent?.model && (
              <Tag className="text-xs" color="blue">
                {agent.model}
              </Tag>
            )}
          </div>
          {sessionTitle && (
            <div className="text-xs text-gray-500 truncate">{sessionTitle}</div>
          )}
        </div>
        <div className="text-xs text-gray-400 shrink-0">
          共 {messageCount} 条消息
        </div>
      </div>
      {sseDisconnected && (
        <Alert
          banner
          type="warning"
          title="实时连接已中断，正在自动重连..."
        />
      )}
    </div>
  );
};

export default ChatHeader;
