import React, { useState } from "react";
import { Sender } from "@ant-design/x";

interface AgentChatInputProps {
  /** 发送消息；Promise<boolean>，false 表示发送失败（保留输入内容） */
  onSend: (
    message: string | { text: string },
    fallbackAgentId?: string,
  ) => Promise<boolean>;
  /** 等待 AI 终态期间置 true，禁用输入 */
  sending?: boolean;
}

const AgentChatInput: React.FC<AgentChatInputProps> = ({
  onSend,
  sending = false,
}) => {
  const [message, setMessage] = useState("");

  return (
    <Sender
      onSubmit={async () => {
        const ok = await onSend(message.trim());
        // 只有发送成功才清空输入框，失败时保留文字供重试
        if (ok) {
          setMessage("");
        }
      }}
      placeholder="输入消息..."
      value={message}
      onChange={setMessage}
      loading={sending}
    />
  );
};

export default AgentChatInput;
