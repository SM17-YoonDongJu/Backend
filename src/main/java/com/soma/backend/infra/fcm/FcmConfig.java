package com.soma.backend.infra.fcm;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.FileInputStream;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Firebase Admin SDK 초기화.
 * fcm.service-account-path 프로퍼티가 설정된 경우에만 FirebaseApp Bean을 생성한다.
 * 미설정 시 FcmService는 stub 모드로 동작한다(@ConditionalOnProperty).
 */
@Configuration
public class FcmConfig {

    @Bean
    @ConditionalOnProperty(name = "fcm.service-account-path")
    public FirebaseApp firebaseApp(@Value("${fcm.service-account-path}") String path) throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        GoogleCredentials credentials;
        try (FileInputStream stream = new FileInputStream(path)) {
            credentials = GoogleCredentials.fromStream(stream);
        }
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build();
        return FirebaseApp.initializeApp(options);
    }
}
