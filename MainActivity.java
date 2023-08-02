package ec.edu.ups.appfacedetection;

import android.Manifest;
import android.graphics.*;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Button;

import com.otaliastudios.cameraview.frame.Frame;
import com.otaliastudios.cameraview.CameraView;

/**
 * Clase MainActivity: Actividad principal de la aplicación.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_REQUEST = 1;

    // Archivo del modelo de detección de rostros
    private final String MODEL_FILE = "modelo.tflite";

    // Puntero al detector nativo
    private long detectorPointer = 0L;

    // Umbral para el mapa de calor
    private final float heatmapThreshold = (float) 0.5;

    // Umbral para la supresión de no máximos (NMS)
    private final float nmsThreshold = (float) 0.3;

    // Número de información por rostro detectado
    private final int nFaceInfo = 5;

    private int frameWidth = 0;
    private int frameHeight = 0;
    private int rotationToUser = 0;
    private Paint _paint = new Paint();
    private SurfaceView surfaceView;
    private CameraView cameraView;
    private boolean surfaceLocked = false;
    private Canvas canvas = null;
    private Path path = new Path();

    private Button switchCameraButton;
    private TextView fpsTextView;

    /**
     * Método onCreate: Se llama al iniciar la actividad.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);

        // Cargar biblioteca nativa
        System.loadLibrary("appfacedetection");

        // Mantener la pantalla encendida
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Solicitar permiso de cámara para Android 6+
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST
        );

        // Cargar el modelo de detección
        detectorPointer = loadDetectorJNI(this.getAssets(), MODEL_FILE);

        setContentView(R.layout.activity_main);
        cameraView = this.findViewById(R.id.camera);
        surfaceView = this.findViewById(R.id.surfaceView);

        cameraView.setLifecycleOwner(this);
        cameraView.addFrameProcessor(frame -> {detectFaceNative(frame);});

        // Inicializar el pincel para dibujar las detecciones
        _paint.setColor(Color.RED);
        _paint.setStyle(Paint.Style.STROKE);
        _paint.setStrokeWidth(10f);

        // Hacer transparente la superficie de dibujo de las detecciones
        surfaceView.setZOrderOnTop(true);
        surfaceView.getHolder().setFormat(PixelFormat.TRANSPARENT);

        fpsTextView = findViewById(R.id.fpsTextView);

        switchCameraButton = findViewById(R.id.button);

        // Botón para cambiar entre la cámara frontal y trasera
        switchCameraButton.setOnClickListener(view -> {
            cameraView.toggleFacing();
        });
    }

    /**
     * Método detectFaceNative: Realiza la detección de rostros en el marco (frame) dado.
     */
    private void detectFaceNative(Frame frame) {
        final long start = SystemClock.elapsedRealtime();
        if (detectorPointer == 0L) {
            detectorPointer = loadDetectorJNI(this.getAssets(), MODEL_FILE);
        }

        frameHeight = frame.getSize().getHeight();
        frameWidth = frame.getSize().getWidth();
        rotationToUser = frame.getRotationToUser();

        float[] detecciones = this.detectJNI(
                detectorPointer,
                frame.getData(),
                heatmapThreshold,
                nmsThreshold,
                frameWidth,
                frameHeight,
                rotationToUser
        );
        //FPS
        long elapsed = SystemClock.elapsedRealtime() - start;
        double fps = 1000f / elapsed;
        int roundedFPS = (int) Math.round(fps * 100); // Redondea y convierte a entero
        Log.i(TAG, String.format("FPS: %f", fps));

        runOnUiThread(() ->
                fpsTextView.setText("   FPS: " + roundedFPS)
        );

        if (!surfaceLocked ) {
            canvas = surfaceView.getHolder().lockCanvas();
            if (detecciones.length == 0) { // Borrar las cajas de detección
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.MULTIPLY);
                surfaceView.getHolder().unlockCanvasAndPost(canvas);
            } else {
                surfaceLocked  = true;
                path.rewind();
            }
        }

        if (canvas != null && surfaceLocked ) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.MULTIPLY);
            for (byte i = 0; i < detecciones.length / nFaceInfo; i++)
                this.drawDetection(canvas, path, rotationToUser, detecciones, i);
            surfaceView.getHolder().unlockCanvasAndPost(canvas);
            surfaceLocked  = false;
        }
    }

    /**
     * Método drawDetection: Dibuja una detección de rostro en el lienzo (canvas) dado.
     */
    private void drawDetection(Canvas canvas, Path p, int rotacion, float[] detecciones, int idx) {
        int ancho;
        int alto;
        // Determinar las dimensiones de ancho y alto de acuerdo a la rotación de la imagen.
        if (rotacion == 0 || rotacion == 180) {
            // La imagen no está rotada (0 grados) o rotada 180 grados.
            ancho = frameWidth;
            alto = frameHeight;
        } else {
            // La imagen está rotada 90 grados o 270 grados.
            ancho = frameHeight;
            alto = frameWidth;
        }

        float escalaX = (float) cameraView.getWidth() / ancho;
        float escalaY = (float) cameraView.getHeight() / alto;
        float desplazamientoX = (float) cameraView.getLeft();
        float desplazamientoY = (float) cameraView.getTop();

        float x1 = desplazamientoX + detecciones[idx * nFaceInfo] * escalaX;
        float y1 = desplazamientoY + detecciones[idx * nFaceInfo + 1] * escalaY;
        float x2 = desplazamientoX + detecciones[idx * nFaceInfo + 2] * escalaX;
        float y2 = desplazamientoY + detecciones[idx * nFaceInfo + 3] * escalaY;
        // Definir el contorno de la detección
        p.moveTo(x1, y1);
        p.lineTo(x1, y2);
        p.lineTo(x2, y2);
        p.lineTo(x2, y1);
        p.lineTo(x1, y1);
        // Dibujar el contorno en el lienzo
        canvas.drawPath(p, _paint);
    }

    /**
     * Método loadDetectorJNI: Carga el modelo desde un archivo en el directorio .
     */
    private native long loadDetectorJNI(AssetManager assetManager, String filename);

    /**
     * Método detectJNI: Realiza la detección de rostros
     */
    private native float[] detectJNI(long detectorPtr, byte[] src,
                                     float heatmapThreshold, float nmsThreshold,
                                     int width, int height, int rotation);
}
