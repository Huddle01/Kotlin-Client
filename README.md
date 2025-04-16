# Huddle01 Kotlin SDK


<p align="center">
  <strong>People-powered
Communication</strong>
</p>

<h3 align="center">
  <a href="https://discord.com/invite/AZ5TRMMP55">Community</a>
  <span> · </span>
  <a href="https://docs.huddle01.com/docs">Documentation</a>
</h3>


# Kotlin SDK for Android Applications

The Huddle01 Kotlin SDK enables seamless integration into Android mobile applications with minimal configuration.
It provides a comprehensive set of methods and event listeners, streamlining real-time audio and video communication while requiring minimal coding.

## **Pre Requisites**

Before using the Huddle01 Kotlin SDK,  make sure your setup has these things:

- Java Development Kit (JDK)
- Android Studio version 3.0 or higher
- Android SDK with API Level 21 or above
- An Android phone or tablet running version 5.0 or later

You can install **Android Studio** from [here](https://developer.android.com/studio)
## Installing the packages:

### **Installation**

To get started with the Huddle01 Kotlin SDK:

• Add the following in your  `settings.gradle.kts`

```kotlin
	
	dependencyResolutionManagement {
		repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
		repositories {
			mavenCentral()
			maven { url 'https://jitpack.io' }
		}
	}
```

• Add the following dependency in your app's `app/build.gradle`

```kotlin

dependencies {
	        implementation 'com.github.Huddle01:Kotlin-Client:1.0.1'
	}
```

### **Add permissions into your project**

- In `/app/Manifests/AndroidManifest.xml`, add the following permissions after `</application>`.

```kotlin

<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />

```

## **Initialising the SDK:**

First you need to configure HuddleClient

```kotlin
import com.huddle01.kotlin_client.HuddleClient

lateinit var huddleClient: HuddleClient
huddleClient = HuddleClient("YOUR_PROJECT_ID", this)

```

## **Joining Room**

```kotlin
import com.huddle01.kotlin_client.HuddleClient 

huddleClient.joinRoom(roomId, token)

```

💡 For more information head to https://docs.huddle01.com/docs/Kotlin

💡 For any help reach out to us on
[Discord](https://discord.com/invite/AZ5TRMMP55)
