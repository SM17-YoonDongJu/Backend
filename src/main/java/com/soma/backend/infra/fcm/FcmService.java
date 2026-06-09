package com.soma.backend.infra.fcm;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * FCM 오프라인 푸시 전송 서비스.
 * FirebaseApp Bean이 없으면(서비스 계정 미설정) stub 모드로 로그만 남긴다.
 * 푸시 실패는 호출 측 채팅 트랜잭션과 분리되어야 하므로 예외를 삼키고 로깅한다.
 */
@Service
@Slf4j
public class FcmService {

    @Autowired(required = false)
    private FirebaseApp firebaseApp;

    public void send(UUID receiverUserId, String title, String body, Map<String, String> data) {
        if (firebaseApp == null) {
            log.debug("[FCM] stub — userId={} title={}", receiverUserId, title);
            return;
        }
        try {
            Message message = Message.builder()
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .putAllData(data)
                    // TODO: receiverUserId로 FCM 등록 토큰 조회 후 setToken(...) (users 테이블 완성 후)
                    .build();
            // TODO: FirebaseMessaging.getInstance(firebaseApp).send(message); (토큰 조회 완료 후 활성화)
            log.info("[FCM] queued — userId={} type={}", receiverUserId, message.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("[FCM] 전송 실패 userId={}", receiverUserId, e);
        }
    }
}
