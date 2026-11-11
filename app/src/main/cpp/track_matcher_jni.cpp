#include "track_matcher_core.h"
#include <jni.h>
#include <string>

static std::string jstring2string(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string str(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return str;
}

static jstring string2jstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

extern "C" {

JNIEXPORT jfloat JNICALL
Java_com_example_audio_NativeTrackMatcher_nativeMatchTracks(
    JNIEnv* env, jclass /* clazz */,
    jstring a1, jstring t1,
    jstring a2, jstring t2,
    jlong dur1, jlong dur2,
    jfloat threshold
) {
    std::string s_a1 = jstring2string(env, a1);
    std::string s_t1 = jstring2string(env, t1);
    std::string s_a2 = jstring2string(env, a2);
    std::string s_t2 = jstring2string(env, t2);

    auto res = track_matcher::matchTracks(s_a1, s_t1, s_a2, s_t2, dur1, dur2, threshold);
    return res.isMatch ? res.totalScore : 0.0f;
}

JNIEXPORT jboolean JNICALL
Java_com_example_audio_NativeTrackMatcher_nativeIsJunk(
    JNIEnv* env, jclass /* clazz */,
    jstring artist, jstring title, jstring path
) {
    std::string a = jstring2string(env, artist);
    std::string t = jstring2string(env, title);
    std::string p = jstring2string(env, path);

    return track_matcher::isJunkAudio(a, t, p) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_audio_NativeTrackMatcher_nativeCleanTrack(
    JNIEnv* env, jclass /* clazz */,
    jstring rawArtist, jstring rawTitle
) {
    std::string a = jstring2string(env, rawArtist);
    std::string t = jstring2string(env, rawTitle);

    auto pt = track_matcher::parseTrack(a, t);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(2, stringClass, nullptr);

    env->SetObjectArrayElement(array, 0, string2jstring(env, pt.cleanArtist));
    env->SetObjectArrayElement(array, 1, string2jstring(env, pt.cleanTitle));

    return array;
}

JNIEXPORT jstring JNICALL
Java_com_example_audio_NativeTrackMatcher_nativeTransliterate(
    JNIEnv* env, jclass /* clazz */,
    jstring input
) {
    std::string s = jstring2string(env, input);
    return string2jstring(env, track_matcher::transliterateRuToEn(s));
}

} // extern "C"
