/**
 * NOTAS
 * para una primera etapa hacerlo solo con roll 0 o 180, en ese caso el pitch que aparece es el real del teléfono y es solo aplicar el ángulo de nivel
 * al nivelar tiene que estar el roll muy cerca de 0
 * al iniciar la medición y durante se debe controlar que esté cerca de esos valores o se descarta la medición y suena alarma
 *
 * lo de getAngleChange no sirve para nada
 * Para una segunda etapa que permita medir con roll lo que se tiene que calcular es cuánto del pitchnivel se tiene que aplicar. Cuando es 0 o 180 es to-do pero en 90 de giro es mucho menos
 *   esto es igual a calcular cuánto hay que variar el pitch para que el roll que se muestra sea igual a la rotación que tiene el bastón. Una vez que el roll del teléfono y el del bastón son
 *     iguales entonces el pitch que se muestra es el real. Creo que sale con solo el roll que se muestra, el pitch que se muestra y elpitchlevel
 */


package com.sisant.android.gnss;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.preference.PreferenceManager;
import android.util.Log;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;

import static java.lang.Math.abs;

public abstract class IMUAndroid implements SensorEventListener {

    private static final String TAG = "sisant_imu";
    private SensorManager sensorManager = null;
    private final float[] rolllog = new float[5];
    private final float[][] acclog = new float[5][3];//esto en 10 hace muy lenta la detección de cambios en el steady
    private final float[][] maglog = new float[5][3];
    private final float[]inclinationlog = new float[30];
    private final float[]rumboreallog = new float[30];
    final float[] accelerometerReading = new float[3];
    final float[] magnetometerReading = new float[3];

    //si tiene dem roll entonces pasa a rolling. Si ya no tiene demasiado roll pasa a roll recover y ahí se queda 2 segundos
    //si no está steady pasa a unsteady y cuando ya vuelve a steady se queda en unsteady recover por 2 segundos
    //si no entonces en steady o en Off si no está activado el feedback
    static final int OFF = 0 ;
    static final int STEADY = 1 ;
    static final int UNSTEADY = 2 ;
    static final int UNSTEADY_RECOVER = 3;
    static final int ROLLING = 4;
    static final int ROLL_RECOVER = 5;
    static final int WAIT_STEADY = 6;
    static final int RECOVERTIME = 2;
    int feedbackStage = 0;
    long feedbackStageChanged;

    static final float TOO_MUCH_ROLL = 3f;

    private final float[] levelAccel = new float[3];
    private final float[] levelMag = new float[3];
    final float[] orientationAnglesSinNivelarRad = new float[4]; //yaw,pitch,rollaccel,rollmag
    final float[] orientationAngles = new float[3]; //serían los nivelados si está activado
    final float[] orientationAnglesNorm = new float[3];
    float rumboReal = 0;
    float inclinacion = 0;

    private SummaryStatistics sumpitch;
    private SummaryStatistics sumroll;
    private SummaryStatistics sumaccel0;
    private SummaryStatistics sumaccel1;
    private SummaryStatistics sumaccel2;
    long startNivelTime = 0; //esta controla que se usen los stats
    final float[] levels = new float[2]; //pitch y roll promedio nivelado
    final float[] levelsSTD = new float[2]; //pitch y roll desv standard final
    final float ungrado = (float) Math.toRadians(1);

    boolean useLevels = false;//poner en true para que se usen los niveles guardados
    SharedPreferences preferences;
    private double antheight;

    public abstract void onUpdate();


    public void start() {
        // Get updates from the accelerometer and magnetometer at a constant rate.
        // To make batch operations more efficient and reduce power consumption,
        // provide support for delaying updates to the application.
        //
        // In this example, the sensor reporting delay is small enough such that
        // the application receives an update before the system checks the sensor
        // readings again.

        if (sensorManager == null)
            sensorManager = (SensorManager) Utils.getMainActivity().getSystemService(Context.SENSOR_SERVICE);

        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        }
        Sensor magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (magneticField != null) {
            sensorManager.registerListener(this, magneticField,
                    SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        }



        preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getMainActivity().getBaseContext());
        levels[0] = preferences.getFloat("levelPitch",0f);
        levels[1] = preferences.getFloat("levelRoll",0f);
        levelAccel[0] = preferences.getFloat("levelAccel0",0f);
        levelAccel[1] = preferences.getFloat("levelAccel1",0f);
        levelAccel[2] = preferences.getFloat("levelAccel2",9.9f);
        levelMag[0] = preferences.getFloat("levelMag0",-20f);
        levelMag[1] = preferences.getFloat("levelMag1",0f);
        levelMag[2] = preferences.getFloat("levelMag2",-18f);
        antheight = preferences.getFloat("altura",0f);
        feedbackStage = WAIT_STEADY;
    }

    public void stop() {
        // Don't receive any more updates from either sensor.
        sensorManager.unregisterListener(this);
    }

    public void startNivel() {
        sumpitch = new SummaryStatistics();
        sumroll = new SummaryStatistics();
        sumaccel0 = new SummaryStatistics();
        sumaccel1 = new SummaryStatistics();
        sumaccel2 = new SummaryStatistics();
        startNivelTime = System.currentTimeMillis();
    }

    public boolean checkNivel() {
        if(sumpitch==null) return true;
        levels[0] = (float) Math.toDegrees(sumpitch.getMean());
        levels[1] = (float) Math.toDegrees(sumroll.getMean());
        levelsSTD[0] = (float) Math.toDegrees(sumpitch.getStandardDeviation());
        levelsSTD[1] = (float) Math.toDegrees(sumroll.getStandardDeviation());
        levelAccel[0] = (float) sumaccel0.getMean();
        levelAccel[1] = (float) sumaccel1.getMean();
        levelAccel[2] = (float) sumaccel2.getMean();


        if (System.currentTimeMillis() - startNivelTime > 5000) {
            if (sumpitch.getStandardDeviation() < ungrado && sumroll.getStandardDeviation() < ungrado) {
                startNivelTime = 0;
                sumroll = sumpitch = null;
                //guardar el resultado
                SharedPreferences.Editor e = preferences.edit();
                e.putFloat("levelPitch", levels[0]);
                e.putFloat("levelRoll", levels[1]);
                e.putFloat("levelAccel0", levelAccel[0]);
                e.putFloat("levelAccel1", levelAccel[1]);
                e.putFloat("levelAccel2", levelAccel[2]);
                e.putFloat("levelMag0", magnetometerReading[0]);
                e.putFloat("levelMag1", magnetometerReading[1]);
                e.putFloat("levelMag2", magnetometerReading[2]);
                e.commit();

                return true;
            }
            if(levelsSTD[0]>3 || levelsSTD[1]>3) {
                sumroll.clear();
                sumpitch.clear();
                sumaccel0.clear();
                sumaccel1.clear();
                sumaccel2.clear();
                startNivelTime=System.currentTimeMillis();
            }
        }
        return false;
    }

    private boolean rumboCerca0() {
        return rumboreallog[0]<10 || rumboreallog[0]>350;
    }
    /**
     * Devuelve en [0] la inclinación y en [1] el rumboreal (0-360)
     * Esto supone que está steady, que no hay mucha variación en los valores y que con solo ver uno ya sabemos por dónde estamos
     * @return
     */
    private float[] getResultAvg() {
        float s[] = new float[2];
        for(int i=0;i<inclinationlog.length;i++) {
            s[0]+=inclinationlog[i];
        }
        for(int i=0;i<rumboreallog.length;i++) {
            if(rumboCerca0() && rumboreallog[i]>180)
                s[1]+=rumboreallog[i]-360; //lo pasa a negativo
            else
                s[1]+=rumboreallog[i];

        }
        s[0] = Math.round(s[0]/inclinationlog.length*10f)/10f;
        s[1] = Math.round(s[1]/rumboreallog.length*10f)/10f;
        if(s[1]<0) s[1]+=360; //si había quedado negativo se pasa a positivo
        return s;
    }

    /**
     * Devuelve en [0]stdev de la inclinación y en [1] del rumboreal
     * @return
     */
    private float[] getResultStdev() {
        float mean[] = getResultAvg();
        float stdev[] = new float[2];
        float var[] = new float[2];
        for(int i=0;i<inclinationlog.length;i++) {
            var[0]+=Math.pow(inclinationlog[i] - mean[0], 2);
        }
        for(int i=0;i<rumboreallog.length;i++) {
            if(rumboCerca0() && rumboreallog[i]>180)
                var[1]+=Math.pow(rumboreallog[i]-360 - mean[1], 2); //lo pasa a negativo
            else
                var[1]+=Math.pow(rumboreallog[i] - mean[1], 2);
        }
        stdev[0] = (float) Math.sqrt(var[0]/inclinationlog.length);
        stdev[1] = (float) Math.sqrt(var[1]/rumboreallog.length);
        return stdev;
    }

    private void addResultLog() {
        //mete los datos recien obtenidos al log
        //pasa el espacio 9 al 10, el 8 al 9  hasta el 1 al 2
        for (int i = 1; i < inclinationlog.length; i++) {
            inclinationlog[inclinationlog.length - i] = inclinationlog[inclinationlog.length - 1 - i];
        }
        for (int i = 1; i < rumboreallog.length; i++) {
            rumboreallog[rumboreallog.length - i] = rumboreallog[rumboreallog.length - 1 - i];
        }
        //meter el último dato en el primer campo
        rumboreallog[0]=rumboReal;
        inclinationlog[0] = inclinacion;

        for (int i = 1; i < rolllog.length; i++) {
            rolllog[rolllog.length - i] = rolllog[rolllog.length - 1 - i];
        }
        rolllog[0] = orientationAngles[2];
    }
    public boolean isSteady() {
        //sacar el máximo y mínimo y ver si la diferencia es mucha
        boolean res;
        float mini=1000,minr=1000;
        float maxi=0,maxr=0;

        for (int i = 0; i < inclinationlog.length; i++) {
            if(inclinationlog[i]>maxi) maxi = inclinationlog[i];
            if(inclinationlog[i]<mini) mini = inclinationlog[i];
        }
        for (int i = 0; i < rumboreallog.length; i++) {
            if(rumboreallog[i]>maxr) maxr = rumboreallog[i];
            if(rumboreallog[i]<minr) minr = rumboreallog[i];
        }
        if(maxr-minr>180) { //esto más bien es que el min r esta cerca de 0 y el max cerca de 360
            float t = maxr-360;
            maxr=minr;
            minr=t;
        }
        if(inclinacion>5)
            res= maxr-minr<10 && maxi-mini<4;//la variación en rumbo real es más fuerte
        else
            res= maxi-mini<4; //para valores de inclinación cercanos a 0 no se toma en cuenta el rumbo porque podría variar muy fuerte
        if(!res) {
            Log.d(TAG,"unsteady");
        }
        return res;
    }
    public boolean isReallySteady() {
        if(!isSteady()) return false;
        float stdev[] = getResultStdev();
        return stdev[0]<1 && stdev[1]<1;
    }

    public boolean isNoRoll() {
        //true si el roll está suficientemente bajo
        Log.d(TAG,"roll avg "+getRollAvg());
        return Math.abs(getRollAvg()) <= TOO_MUCH_ROLL;
    }


    private void addReadingLog(boolean acc,boolean mag) {
        //mete los datos recien obtenidos al log
        //pasa el espacio 9 al 10, el 8 al 9  hasta el 1 al 2
        if(acc) {
            for (int i = 1; i < acclog.length; i++) {
                acclog[acclog.length - i][0] = acclog[acclog.length - 1 - i][0];
                acclog[acclog.length - i][1] = acclog[acclog.length - 1 - i][1];
                acclog[acclog.length - i][2] = acclog[acclog.length - 1 - i][2];
            }
            //meter el último dato en el primer campo
            acclog[0][0] = accelerometerReading[0];
            acclog[0][1] = accelerometerReading[1];
            acclog[0][2] = accelerometerReading[2];
        }
        if(mag) {
            for (int i = 1; i < maglog.length; i++) {
                maglog[maglog.length - i][0] = maglog[maglog.length - 1 - i][0];
                maglog[maglog.length - i][1] = maglog[maglog.length - 1 - i][1];
                maglog[maglog.length - i][2] = maglog[maglog.length - 1 - i][2];
            }
            maglog[0][0] = magnetometerReading[0];
            maglog[0][1] = magnetometerReading[1];
            maglog[0][2] = magnetometerReading[2];
        }


    }


    private float getRollAvg() {
        float s=0;
        for(int i=0;i<rolllog.length;i++) {
            s+=rolllog[i];
        }
        return s/rolllog.length;
    }

    private void getReadingAvg() {
        //sacar el promedio y ponerlo en los valores
        accelerometerReading[0] = 0f;
        accelerometerReading[1] = 0f;
        accelerometerReading[2] = 0f;
        for (int i = 0; i < acclog.length; i++) {
            accelerometerReading[0] += acclog[i][0] / (float) acclog.length;
            accelerometerReading[1] += acclog[i][1] / (float) acclog.length;
            accelerometerReading[2] += acclog[i][2] / (float) acclog.length;
        }
        magnetometerReading[0] = 0f;
        magnetometerReading[1] = 0f;
        magnetometerReading[2] = 0f;
        for (int i = 0; i < maglog.length; i++) {
            magnetometerReading[0] += maglog[i][0] / (float) maglog.length;
            magnetometerReading[1] += maglog[i][1] / (float) maglog.length;
            magnetometerReading[2] += maglog[i][2] / (float) maglog.length;
        }
    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do something here if sensor accuracy changes.
        // You must implement this callback in your code.
    }

    // Get readings from accelerometer and magnetometer. To simplify calculations,
    // consider storing these readings as unit vectors.
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading,
                    0, accelerometerReading.length);

            //cuando se recibe el acc se hace todo lo de abajo

        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading,
                    0, magnetometerReading.length);
            if (startNivelTime == 0) { //se usa lo de promediar solo si no estamos nivelando
                addReadingLog(false, true);
            }
            return; //el mag solo guarda y vuelve
        }
        else {
            return;
        }


        if (startNivelTime == 0) { //se usa lo de promediar solo si no estamos nivelando
            addReadingLog(true,false);
            getReadingAvg();
        }
        updateOrientationAngles();

        //para la nivelación se usa los ángulos crudos en radianes y se guarda los datos del accel
        if (startNivelTime > 0) {
            sumpitch.addValue(orientationAngles[1]);
            sumroll.addValue(orientationAngles[2]);
            sumaccel0.addValue(accelerometerReading[0]);
            sumaccel1.addValue(accelerometerReading[1]);
            sumaccel2.addValue(accelerometerReading[2]);
        }


        //pasar a grados y redondear
        orientationAngles[0] = Math.round(Math.toDegrees(orientationAngles[0]) * 10f) / 10f;//yaw
        orientationAngles[1] = Math.round(Math.toDegrees(orientationAngles[1]) * 10f) / 10f;//pitch
        orientationAngles[2] = Math.round(Math.toDegrees(orientationAngles[2]) * 10f) / 10f;//roll
        //aplicar declinación
        Location currentLoc = MainActivity.fallbackLocation;
        if (currentLoc != null) {
            orientationAngles[0] += new GeomagneticField((float) currentLoc.getLatitude(), (float) currentLoc.getLongitude(), (float) currentLoc.getAltitude(), System.currentTimeMillis()).getDeclination();
        } else {
            //aplicar algo aprox -2 es en centro de país y más o menos el promedio
            orientationAngles[0] += -2f;
        }

        //calcular el yaw normalizado, este va a ser la dirección en la que el teléfono apunta si estuviera con la pantalla viendo ahacia arriba o si está vuelto
        //   entonces si se volviera con movimiento de pitch únicamente, o sea el yaw norm no varia si por medio de pitch se vuelve la pantalla
        // además es de 0-360
        orientationAnglesNorm[0] = (orientationAngles[0] < 0 ? orientationAngles[0] + 360 : orientationAngles[0]);//pasar a 0-360
        if(abs(Math.toDegrees(orientationAnglesSinNivelarRad[3]))>90) { // está vuelto, entonces girar 180
            orientationAnglesNorm[0]-=180;
            if (orientationAnglesNorm[0] < 0) orientationAnglesNorm[0] += 360;
        }


        //ETAPA 1 solo con roll=0


        //La inclinación se define como el ángulo entre el suelo y el bastón, si está parado es 90, rango 0-90
        inclinacion = 90 - Math.abs(orientationAngles[1]);

        //aplicar los niveles guardados
        //if(useLevels && startNivelTime==0) {
           /* //el nivel guardado es lo que dice el pich con el bastón nivelado, si es menor al nivel entonces está inclinado al frente, si es mayor está hacia atrás
            //si es hacia atrás y la pantalla se vuelve entonces se debe sumar todo lo que se hizo hacia atrás para llegar a vertical más el pitch que se indica
            if(abs(Math.toDegrees(orientationAnglesSinNivelarRad[3]))<90) { //no está vuelto
                inclinacion = abs(orientationAngles[1] - levels[0]);
                rumboReal = orientationAngles[0]+(abs(orientationAngles[1])<abs(levels[0])?0:180);
            }
            else {
                inclinacion = 90 + levels[0] + 90 + orientationAngles[1];
                //cuando está vuelto siempre es el yaw real
                rumboReal = orientationAngles[0];
            }*/

            //el yaw siempre es hacia donde apunta la parte de arriba del teléfono
            //el rumbo se debe girar 180 si el bastón está inclinado hacia atrás, osea el pitch ajustado es negativo
           // if(orientationAngles[1]<0)
           //     rumboReal =

       // }

        //si el pitch de bastón es hacia atrás (negativo) entonces el rumbo real es 180 grados vuelto
        rumboReal=orientationAnglesNorm[0];
        if(orientationAngles[1]<0) {
            rumboReal-=180;
            if (rumboReal < 0) rumboReal += 360;
        }





       /* //ETAPA 2 calcular el rumbo real aplicando el roll
        // ESTO yo creo que está to-do malo porque tiene una idea de roll que no corresponde con nada relacionado al bastón
        //con estos datos la rotación necesaria para hacer el roll 0 es atan(roll/pitch). Si el pitch es 0 la rotación es 90
        double rot = 0;
        if (orientationAnglesNorm[2] != 0) {
            if(orientationAnglesNorm[1]==0)
                rot = 90f;
            else
                rot = Math.toDegrees(Math.atan(orientationAnglesNorm[2] / orientationAnglesNorm[1]));
        }
        rumboReal = abs(orientationAnglesNorm[2])<90?orientationAngles[0]:orientationAngles[0]+180;//Acá se usa el yaw original, no el normalizado. Pero si el roll es mayor a 90 eso indica que está con la pantalla vuelta y el rumbo real es para atrás
        rumboReal += rot;
        if (rumboReal < 0) rumboReal += 360;
        if (rumboReal > 360) rumboReal -= 360;


        //calcular la inclinación original, que es la misma que la final, y es el pitch una vez que roll es 0. Esto se saca con la raíz cuadrada de la suma de los cuadrados de pitch y roll
        inclinacion = (float) Math.toDegrees(Math.atan(Math.sqrt(Math.pow(Math.tan(Math.toRadians(orientationAnglesNorm[1])), 2) + Math.pow(Math.tan(Math.toRadians(orientationAnglesNorm[2])), 2))));
*/
        addResultLog();

        onUpdate();
        //Log.d("sisant-sensors",o1+","+o2+" "+o3);
    }




    // Compute the three orientation angles based on the most recent readings from
    // the device's accelerometer and magnetometer.
    private void updateOrientationAngles() {
        //sacar el roll solo con acce
        double gx, gy, gz;
        gx = accelerometerReading[0] / 9.81f;
        gy = accelerometerReading[1] / 9.81f;
        gz = accelerometerReading[2] / 9.81f;
        // http://theccontinuum.com/2012/09/24/arduino-imu-pitch-roll-from-accelerometer/
        //float pitch = (float) -Math.atan(gy / Math.sqrt(gx * gx + gz * gz));
        float roll = (float) -Math.atan(gx / Math.sqrt(gy * gy + gz * gz));
        //pitch = Math.round(Math.toDegrees(pitch) * 10f) / 10f;
        //roll = Math.round(Math.toDegrees(roll) * 10f) / 10f;


        final float[] rotationMatrix = new float[9];

        // Update rotation matrix, which is needed to update orientation angles.
        SensorManager.getRotationMatrix(rotationMatrix, null,
                accelerometerReading, magnetometerReading);

        SensorManager.getOrientation(rotationMatrix, orientationAnglesSinNivelarRad);
        orientationAnglesSinNivelarRad[3]= orientationAnglesSinNivelarRad[2];
        orientationAnglesSinNivelarRad[2]= roll;




        //si la pantalla está vuelta se debe sumar el pitch
        if(abs(Math.toDegrees(orientationAnglesSinNivelarRad[3]))>90) { //este valor es mayor a 90 cuando está vuelto, o sea viendo hacia abajo la pantalla
            //el pitch reportado en ese caso por el sensor es igual a que si no estuviera vuelto
            //siempre es negativo porque positivo sería con la pantalla al revés, la parte de arriba del teléfono apuntando al suelo
            orientationAnglesSinNivelarRad[1] = (float) (-Math.PI - orientationAnglesSinNivelarRad[1]);
        }

        orientationAngles[0]= orientationAnglesSinNivelarRad[0];
        orientationAngles[1]= orientationAnglesSinNivelarRad[1];
        orientationAngles[2]= orientationAnglesSinNivelarRad[2];


        //aplicar los niveles guardados
        if(useLevels && startNivelTime==0) {

          /*  float yaw = orientationAnglesSinNivelar[0];

            final float[] levelRotationMatrix = new float[9];
            //calcular matriz nivelada, luego sacar la diferencia con la actual y esos serían los ángulos nuevos
            SensorManager.getRotationMatrix(levelRotationMatrix, null,
                    levelAccel, levelMag);
            SensorManager.getAngleChange(orientationAngles,rotationMatrix,levelRotationMatrix);
            Log.d(TAG,"angle change "+Utils.round(Math.toDegrees(orientationAngles[0]),1)+ " "+Utils.round(Math.toDegrees(orientationAngles[1]),1)+ " "+Utils.round(Math.toDegrees(orientationAngles[2]),1));
            orientationAngles[0] = yaw;*/

          /*  //primero hay que ver si se tiene un roll con respecto al roll original nivelado porque hay que rotarlo de vuelta al roll original para poder aplicar los niveles guardados
            float diffroll = levels[1] - toNormDegrees(orientationAngles[2]);

            orientationAngles[1]-=Math.toRadians(levels[0]);
            orientationAngles[2]-=Math.toRadians(levels[1]);*/

            orientationAngles[1]-=Math.toRadians(levels[0]); //se resta el pitchlevel para tener el pitch del bastón


        }
        else {
        }
        //ESTA EN RADIANES
    }

    /**
     * Devuelve en grados entre 0-360
     * @param rad
     * @return
     */
    private float toNormDegrees(float rad) {
        float deg = (float) Math.toDegrees(rad);
        if(deg < 0) deg+= 360;
        return deg;

    }

    //ver la doc arriba donde se definen las stages
    public int feedbackStageUpdate() {
        if(feedbackStage==WAIT_STEADY && !isSteady()) return WAIT_STEADY;
        int lastStage = feedbackStage;
        if(!isNoRoll()) {
            feedbackStage = ROLLING;
            //Log.d(TAG,"too much roll");
        }
        else {
            if(lastStage==ROLLING || (lastStage==ROLL_RECOVER && System.currentTimeMillis()-feedbackStageChanged<RECOVERTIME*1000))
                feedbackStage = ROLL_RECOVER;
            else {
                if(!isSteady())
                    feedbackStage = UNSTEADY;
                else {
                    if(lastStage == UNSTEADY  || (lastStage==UNSTEADY_RECOVER && System.currentTimeMillis()-feedbackStageChanged<RECOVERTIME*1000))
                        feedbackStage = UNSTEADY_RECOVER;
                    else
                        feedbackStage = STEADY;
                }
            }
        }
        if(feedbackStage!=lastStage) {
            feedbackStageChanged = System.currentTimeMillis();
            Log.d(TAG,"stage changed "+lastStage+" -> "+feedbackStage);
        }
        return feedbackStage;
    }

    /**
     *
     * @param location
     * @return true si es válido el resultado. Puede ser inválido si no se tiene certeza de la inclinación o si no es apropiada para una buena medición
     */
    public boolean applyRotation(Location location) {

        if(!isReallySteady()) return false;
        if(!isNoRoll()) return false;


        float[] r = getResultAvg(); //inclinacion y rumboreal
        ///

        //calcular la altura a y base b del triángulo que forma el bastón con el suelo.
        float a = (float) (antheight * Math.sin(Math.toRadians(r[0])) / Math.sin(Math.PI/2));
        float b = (float) (antheight * Math.sin(Math.toRadians(90-r[0])) / Math.sin(Math.PI/2));
        //a la altura que indica el GPS se le debe sumar la altura del bastón y restar la altura inclinada, esa diferencia es lo necesario para llegar a la altura no inclinada
        location.setAltitude(location.getAltitude()+antheight - a);
        //calcular los componentes en X y Y de la base, según el rumbo
        float x = (float) (b * Math.sin(Math.toRadians(r[1])) / Math.sin(Math.PI/2));
        float y = (float) (b * Math.sin(Math.toRadians(90-r[1])) / Math.sin(Math.PI/2));
        //convertir la ubicación a CRTM05 para aplicar los offsets y luego de vuelta
        double crtmxy[] = Utils.ToCrtm05_CTS(location.getLatitude(),location.getLongitude());
        crtmxy[0]-=x; //x positivo sería que el bastón se corrió hacia el este, se resta eso para volver a lo que sería el x no inclinado
        crtmxy[1]-=y;
        double[] lola = Utils.ToLL_CTS(crtmxy[0],crtmxy[1]);
        location.setLongitude(lola[0]);
        location.setLatitude(lola[1]);

        Log.d(TAG,"rotate location. Incli:"+r[0]+"  rumbo:"+r[1]+" alt sobre suelo:"+a+" offset x:"+x+" offset y:"+y);
        return true;
    }
}
