#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "face-detection.h"

#define TAG "NativeLib"

// Función para rotar una matriz de imagen en función del ángulo proporcionado.
void rotateMat(cv::Mat &matImage, int rotation);

// Implementación de la función de rotación.
void rotateMat(cv::Mat &matImage, int rotation) {
    if (rotation == 90) {
        transpose(matImage, matImage);
        flip(matImage, matImage, 1); // Transposición + volteo(1) = Rotación en sentido de las agujas del reloj.
    } else if (rotation == 270) {
        transpose(matImage, matImage);
        flip(matImage, matImage, 0); // Transposición + volteo(0) = Rotación en sentido contrario a las agujas del reloj.
    } else if (rotation == 180) {
        flip(matImage, matImage, -1); // Volteo(-1) = Rotación de 180 grados.
    }
}

// Función JNI para cargar el modelo  detector de rostros.
extern "C"
JNIEXPORT jlong JNICALL
Java_ec_edu_ups_appfacedetection_MainActivity_loadDetectorJNI(JNIEnv *env, jobject thiz,
                                                              jobject asset_manager,
                                                              jstring filename) {
    char* buffer = nullptr;
    long size = 0;
    const char* modelpath = env->GetStringUTFChars(filename, 0);

    if (!(env->IsSameObject(asset_manager, NULL))) {
        // Obtener el gestor de archivos de Android.
        AAssetManager *mgr = AAssetManager_fromJava(env, asset_manager);
        // Abrir el archivo de modelo como un activo.
        AAsset *asset = AAssetManager_open(mgr, modelpath, AASSET_MODE_UNKNOWN);
        assert(asset != nullptr);

        // Obtener el tamaño  (modelo).
        size = AAsset_getLength(asset);
        // Leer el contenido  en un búfer.
        buffer = (char *) malloc(sizeof(char) * size);
        AAsset_read(asset, buffer, size);
        AAsset_close(asset);
    }

    // Crear un puntero al objeto FaceDetector y devolverlo.
    jlong detectorPointer = (jlong) new FaceDetector(buffer, size, false);
    free(buffer); // Liberar el búfer de memoria.
    return detectorPointer;
}

// Función JNI para realizar la detección de rostros.
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_ec_edu_ups_appfacedetection_MainActivity_detectJNI(JNIEnv *env, jobject thiz,
                                                        jlong detector_ptr, jbyteArray src,
                                                        jfloat heatmap_threshold,
                                                        jfloat nms_threshold, jint width,
                                                        jint height, jint rotation) {

    // Convertir los bytes de la imagen en un arreglo de bytes.
    jbyte *yuv = env->GetByteArrayElements(src, 0);
    // Crear una matriz de imagen a partir de los datos YUV.
    cv::Mat my_yuv(height + height / 2, width, CV_8UC1, yuv);
    // Crear una matriz de imagen en formato RGB.
    cv::Mat frame(height, width, CV_8UC4);

    // Convertir la imagen YUV a formato RGB.
    cv::cvtColor(my_yuv, frame, cv::COLOR_YUV2BGRA_NV21);

    // Rotar la imagen según el ángulo proporcionado.
    rotateMat(frame, rotation);

    // Liberar la memoria del arreglo de bytes de la imagen.
    env->ReleaseByteArrayElements(src, yuv, 0);

    // Obtener el puntero al objeto FaceDetector.
    FaceDetector* detector = (FaceDetector*) detector_ptr;

    // Realizar la detección de rostros en la imagen.
    std::vector<FaceInfo> faces;
    detector->detect(frame, faces, heatmap_threshold, nms_threshold);

    // Preparar los resultados para devolver a Java.
    int resLen = faces.size() * N_FACE_ATTB;
    jfloat jfaces[resLen];
    for (int i = 0; i < faces.size(); i++) {
        jfaces[i * N_FACE_ATTB] = faces[i].x1;
        jfaces[i * N_FACE_ATTB + 1] = faces[i].y1;
        jfaces[i * N_FACE_ATTB + 2] = faces[i].x2;
        jfaces[i * N_FACE_ATTB + 3] = faces[i].y2;
        jfaces[i * N_FACE_ATTB + 4] = faces[i].score;
    }

    // Crear un arreglo de números en JNI.
    jfloatArray detections = env->NewFloatArray(resLen);
    // Copiar los datos de jfaces al arreglo en JNI.
    env->SetFloatArrayRegion(detections, 0, resLen, jfaces);

    // Devolver el arreglo de detecciones a Java.
    return detections;
}