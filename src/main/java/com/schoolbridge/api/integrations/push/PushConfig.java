package com.schoolbridge.api.integrations.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.FileInputStream;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Initializes the Firebase Admin SDK when FCM is enabled. */
@Configuration
@ConditionalOnProperty(name = "schoolbridge.push.fcm.enabled", havingValue = "true")
public class PushConfig {

  @Bean
  public FirebaseApp firebaseApp(PushProperties props) throws IOException {
    String credFile = props.getFcm().getCredentialsFile();
    GoogleCredentials credentials =
        StringUtils.hasText(credFile)
            ? GoogleCredentials.fromStream(new FileInputStream(credFile))
            : GoogleCredentials.getApplicationDefault();
    FirebaseOptions options = FirebaseOptions.builder().setCredentials(credentials).build();
    return FirebaseApp.initializeApp(options);
  }
}

