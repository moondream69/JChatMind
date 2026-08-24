import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { message as antdMessage } from "antd";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import ChatHeader from "./agentChatView/ChatHeader.tsx";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import {
  createChatMessage,
  createChatSession,
  deleteChatSession,
  getChatMessagesBySessionId,
  getChatSession,
  type ChatSessionVO,
} from "../../api/api.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import { useChatSse } from "../../hooks/useChatSse.ts";
import { getAgentEmoji } from "../../utils";
import type { ChatMessageVO, SseMessageType } from "../../types";

/** 等待 AI 终态（AI_DONE / AI_ERROR）的超时兜底：终态可能因断线或不存在的会话而丢失 */
const SENDING_TIMEOUT_MS = 180_000;

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const navigate = useNavigate();
  const { agents } = useAgents();
  const { refreshChatSessions } = useChatSessions();

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);
  const [chatSession, setChatSession] = useState<ChatSessionVO | null>(null);
  // 等待 AI 终态期间置 true，禁用重复发送
  const [sending, setSending] = useState(false);
  const [displayAgentStatus, setDisplayAgentStatus] = useState(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<SseMessageType>();

  // 最新 chatSessionId 的引用，用于异步回调中的会话守卫（防止跨会话污染）
  const sessionIdRef = useRef(chatSessionId);
  useEffect(() => {
    sessionIdRef.current = chatSessionId;
  }, [chatSessionId]);

  // sending 超时兜底：断线/切会话会导致终态丢失，超时后复位并提示
  const sendingTimeoutRef = useRef<number | null>(null);

  const resetSending = useCallback(() => {
    setSending(false);
    if (sendingTimeoutRef.current !== null) {
      window.clearTimeout(sendingTimeoutRef.current);
      sendingTimeoutRef.current = null;
    }
  }, []);

  const armSendingTimeout = useCallback(() => {
    if (sendingTimeoutRef.current !== null) {
      window.clearTimeout(sendingTimeoutRef.current);
    }
    sendingTimeoutRef.current = window.setTimeout(() => {
      sendingTimeoutRef.current = null;
      setSending(false);
      antdMessage.warning("长时间未收到 AI 回复，可能连接已中断，可稍后重试");
    }, SENDING_TIMEOUT_MS);
  }, []);

  // 卸载时清理超时 timer
  useEffect(() => {
    return () => {
      if (sendingTimeoutRef.current !== null) {
        window.clearTimeout(sendingTimeoutRef.current);
      }
    };
  }, []);

  // 按 id 合并消息：SSE 推送与后端拉取可能交叉到达，合并避免重复和遗漏
  const mergeMessages = useCallback((incoming: ChatMessageVO[]) => {
    setMessages((prev) => {
      const result = [...prev];
      for (const incomingMessage of incoming) {
        const idx = result.findIndex((m) => m.id === incomingMessage.id);
        if (idx >= 0) {
          result[idx] = incomingMessage;
        } else {
          result.push(incomingMessage);
        }
      }
      return result;
    });
  }, []);

  // 会话变化时先清空旧状态，再重新拉取消息与会话信息
  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    let cancelled = false;
    (async () => {
      // 上一会话的等待/状态不带入新会话
      resetSending();
      setMessages([]);
      setChatSession(null);
      setDisplayAgentStatus(false);
      setAgentStatusText("");
      setAgentStatusType(undefined);
      try {
        const [messagesResp, sessionResp] = await Promise.all([
          getChatMessagesBySessionId(chatSessionId),
          getChatSession(chatSessionId),
        ]);
        if (cancelled) return;
        mergeMessages(messagesResp.chatMessages);
        setChatSession(sessionResp.chatSession);
      } catch {
        // http.ts 已统一 toast，无需重复提示
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [chatSessionId, mergeMessages, resetSending]);

  // 当前会话对应的智能体
  const currentAgent = useMemo(
    () => agents.find((agent) => agent.id === chatSession?.agentId),
    [agents, chatSession],
  );

  // SSE 订阅（连接随 chatSessionId 或组件卸载自动关闭）
  const sseConnection = useChatSse(chatSessionId, {
    onGeneratedContent: (message) => mergeMessages([message]),
    onStatusText: (type, statusText) => {
      setDisplayAgentStatus(true);
      setAgentStatusText(statusText);
      setAgentStatusType(type);
    },
    onDone: () => {
      resetSending();
      setDisplayAgentStatus(false);
      setAgentStatusText("");
      setAgentStatusType(undefined);
    },
    onError: () => {
      resetSending();
      antdMessage.error("AI 处理失败，请查看消息列表中的错误说明");
      setDisplayAgentStatus(false);
      setAgentStatusText("");
      setAgentStatusType(undefined);
    },
    onOpen: () => {
      // 连接建立/自动重连成功后补偿拉取（断线期间的消息可能因推送落到空连接上而丢失）
      if (!chatSessionId) return;
      getChatMessagesBySessionId(chatSessionId)
        .then((resp) => {
          if (sessionIdRef.current === chatSessionId) {
            mergeMessages(resp.chatMessages);
          }
        })
        .catch(() => {
          // http.ts 已统一 toast
        });
    },
  });

  // 发送消息；返回是否成功（失败时输入框保留内容）
  const handleSendMessage = useCallback(
    async (
      value: string | { text: string },
      fallbackAgentId?: string,
    ): Promise<boolean> => {
      const message = typeof value === "string" ? value : value.text;
      const trimmed = message.trim();
      if (!trimmed) return false;

      // agentId 校验提前：会话信息未就绪（加载中）或智能体已不存在时直接拦截，
      // 避免以空 agentId 发送导致后端工厂装配失败且无任何提示
      const agentId = chatSessionId
        ? chatSession?.agentId ?? ""
        : fallbackAgentId ?? "";
      if (!agentId) {
        antdMessage.warning("会话信息尚未就绪或智能体已不存在，请稍后再试");
        return false;
      }

      setSending(true);

      try {
        if (!chatSessionId) {
          // 新建会话：创建会话 → 发首条消息 → 刷新列表 → 进入会话
          const response = await createChatSession({
            agentId,
            title: trimmed.slice(0, 20),
          });
          try {
            await createChatMessage({
              agentId,
              sessionId: response.chatSessionId,
              role: "user",
              content: trimmed,
            });
          } catch {
            // 会话已建但首条消息失败：清理孤儿会话，避免重试累积空会话
            await deleteChatSession(response.chatSessionId).catch(() => {});
            setSending(false);
            return false;
          }
          await refreshChatSessions();
          armSendingTimeout();
          navigate(`/chat/${response.chatSessionId}`, { replace: true });
          return true;
        }

        // 已有会话：直接发送
        await createChatMessage({
          agentId,
          sessionId: chatSessionId,
          role: "user",
          content: trimmed,
        });
        // 发送成功后拉取刷新（按 id 合并，且先确认会话未切换，避免污染新会话列表）
        const messagesResp = await getChatMessagesBySessionId(chatSessionId);
        if (sessionIdRef.current === chatSessionId) {
          mergeMessages(messagesResp.chatMessages);
        }
        armSendingTimeout();
        return true;
      } catch {
        // http.ts 已统一 toast；返回 false 让输入框保留文字
        setSending(false);
        return false;
      }
    },
    [
      chatSessionId,
      chatSession,
      navigate,
      refreshChatSessions,
      mergeMessages,
      armSendingTimeout,
    ],
  );

  // 无会话：空态视图（选择智能体 + 引导）
  if (!chatSessionId) {
    return (
      <EmptyAgentChatView
        agents={agents}
        sending={sending}
        onSend={handleSendMessage}
      />
    );
  }

  // 有会话：顶部信息条 + 消息列表 + 输入框
  return (
    <div className="flex flex-col h-full">
      <ChatHeader
        agent={currentAgent}
        sessionTitle={chatSession?.title}
        messageCount={messages.length}
        sseDisconnected={sseConnection === "error"}
      />
      <AgentChatHistory
        messages={messages}
        agentEmoji={currentAgent ? getAgentEmoji(currentAgent.id) : undefined}
        agentName={currentAgent?.name}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
      />
      <div className="border-t border-gray-200 p-4 bg-white">
        <AgentChatInput onSend={handleSendMessage} sending={sending} />
      </div>
    </div>
  );
};

export default AgentChatView;
