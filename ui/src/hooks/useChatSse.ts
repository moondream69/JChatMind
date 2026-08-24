import { useEffect, useRef, useState } from "react";
import { SSE_BASE_URL } from "../api/http.ts";
import type { ChatMessageVO, SseMessage, SseMessageType } from "../types";

export type SseConnectionState = "disconnected" | "open" | "error";

export interface UseChatSseOptions {
  /** AI_GENERATED_CONTENT：AI 产出的消息 */
  onGeneratedContent: (message: ChatMessageVO) => void;
  /** AI_PLANNING / AI_THINKING / AI_EXECUTING：状态文本 */
  onStatusText: (type: SseMessageType, statusText: string) => void;
  /** AI_DONE：Agent 正常完成 */
  onDone: () => void;
  /** AI_ERROR：Agent 异常，后端已落库错误消息 */
  onError: () => void;
  /** 连接建立/自动重连成功时触发，用于补偿拉取断线期间丢失的消息 */
  onOpen?: () => void;
}

/**
 * 聊天会话 SSE 订阅（唯一入口）。
 * EventSource 在连接断开时自动重连，这里只暴露连接状态供 UI 提示。
 */
export const useChatSse = (
  chatSessionId: string | undefined,
  options: UseChatSseOptions,
): SseConnectionState => {
  const [connectionState, setConnectionState] =
    useState<SseConnectionState>("disconnected");
  // 用 ref 持有回调，避免每次渲染重建连接
  const optionsRef = useRef(options);
  useEffect(() => {
    optionsRef.current = options;
  });

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }

    const es = new EventSource(`${SSE_BASE_URL}/connect/${chatSessionId}`);

    es.onopen = () => {
      setConnectionState("open");
      optionsRef.current.onOpen?.();
    };

    es.onerror = () => {
      // EventSource 会按规范自动重连，这里仅标记状态供 UI 提示
      setConnectionState("error");
    };

    es.addEventListener("message", (event) => {
      const sseMessage = JSON.parse(event.data) as SseMessage;
      switch (sseMessage.type) {
        case "AI_GENERATED_CONTENT":
          optionsRef.current.onGeneratedContent(sseMessage.payload.message!);
          break;
        case "AI_PLANNING":
        case "AI_THINKING":
        case "AI_EXECUTING":
          optionsRef.current.onStatusText(
            sseMessage.type,
            sseMessage.payload.statusText ?? "",
          );
          break;
        case "AI_DONE":
          optionsRef.current.onDone();
          break;
        case "AI_ERROR":
          optionsRef.current.onError();
          break;
        default:
          console.warn("Unknown SSE message type:", sseMessage.type);
      }
    });

    return () => es.close();
  }, [chatSessionId]);

  return connectionState;
};
