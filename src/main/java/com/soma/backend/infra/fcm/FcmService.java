package com.soma.backend.infra.fcm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FCM/APNs 순수 발송 어댑터(infra leaf). 토큰 목록을 받아 개별 발송하고, 죽은 토큰만 반환한다.
 *
 * <p>도메인(DeviceTokenRepository)을 알지 못한다 — 토큰 로드·죽은 토큰 삭제는 소유 도메인의
 * PushNotificationService가 담당한다. FirebaseApp 빈이 없으면(stub) 빈 리스트를 반환하고 로그만 남기며
 * 부수효과가 전혀 없다. 한 토큰 실패가 나머지 발송을 막지 않도록 per-token try/catch로 격리하고,
 * 일시 오류(UNAVAILABLE·INTERNAL·QUOTA_EXCEEDED 등)는 죽은 토큰으로 취급하지 않는다.
 *
 * <p>APNs는 Firebase를 경유하므로 별도 연동이 필요 없다. 도메인 Notification과의 클래스명 충돌을 피하려
 * 이 클래스는 {@code com.google.firebase.messaging.Notification}만 import한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FcmService {

  private final ObjectProvider<FirebaseApp> firebaseAppProvider;

  /**
   * tokens로 푸시를 발송하고, 죽은 토큰(UNREGISTERED/INVALID_ARGUMENT) 목록을 반환한다.
   * FirebaseApp이 구성되지 않았거나 tokens가 비면 발송 없이 빈 리스트를 반환한다.
   *
   * @param data null이면 빈 데이터로 처리한다.
   * @return 정리해야 할 죽은 토큰 목록(없으면 빈 리스트)
   */
  public List<String> send(List<String> tokens, String title, String body, Map<String, String> data) {
    FirebaseApp app = firebaseAppProvider.getIfAvailable();
    if (app == null) {
      log.info("FirebaseApp 미구성(stub 모드): 푸시 발송을 건너뜁니다.");
      return List.of();
    }
    if (tokens == null || tokens.isEmpty()) {
      return List.of();
    }
    Map<String, String> payloadData = data == null ? Map.of() : data;
    FirebaseMessaging messaging = FirebaseMessaging.getInstance(app);
    List<String> deadTokens = new ArrayList<>();
    for (String token : tokens) {
      sendOne(messaging, token, title, body, payloadData, deadTokens);
    }
    return deadTokens;
  }

  private void sendOne(FirebaseMessaging messaging, String token, String title, String body,
      Map<String, String> data, List<String> deadTokens) {
    try {
      Message message = Message.builder()
          .setToken(token)
          .setNotification(Notification.builder().setTitle(title).setBody(body).build())
          .putAllData(data)
          .build();
      messaging.send(message);
    } catch (FirebaseMessagingException ex) {
      MessagingErrorCode errorCode = ex.getMessagingErrorCode();
      if (errorCode == MessagingErrorCode.UNREGISTERED || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
        deadTokens.add(token);
        log.warn("죽은 디바이스 토큰 감지(errorCode={}): 정리 대상에 추가합니다.", errorCode);
      } else {
        log.warn("푸시 발송 일시 실패(errorCode={}): 토큰을 유지합니다.", errorCode, ex);
      }
    }
  }
}
