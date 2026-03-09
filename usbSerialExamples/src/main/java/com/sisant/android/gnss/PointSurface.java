package com.sisant.android.gnss;


import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;


/**
 * Defines a custom SurfaceView class which handles the drawing thread
 **/
public class PointSurface extends SurfaceView implements SurfaceHolder.Callback, View.OnTouchListener//, Runnable
{

    PosStatsMain posStats;
    boolean ZredrawNeeded = true;
    boolean XYredrawNeeded = true;
    boolean FullredrawNeeded = true;

    int colorBackground = 0xffffffff;

    //las ubicaciones en pixeles de los diferentes elementos, le llamabos bar a la barra para la altura y area al cuadro para el XY  La altura de la barra y el ancho del cuadro son iguales
    private int graphTop, ZgraphLeft, XYgraphLeft, XYgraphWidth;

    /**
     * Holds the surface frame
     */
    private SurfaceHolder holder;

    /**
     * Draw thread
     */
    private Thread drawThread;

    /**
     * True when the surface is ready to draw
     */
    private boolean surfaceReady = false;


    /**
     * Drawing thread flag
     */

    private boolean drawingActive = false;

    /**
     * Paint for drawing the sample rectangle
     */
    private Paint graphPaint = new Paint();
    private Paint scaleLinePaint = new Paint();
    private Paint scaleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private Paint erasePaint = new Paint();


    private static final String TAG = "sisant-surface";
    private static final int SCALE_MARGIN_BOTTOM = 4; //pixeles libres sobre el gráfico

    private Canvas fullCanvas;

    public PointSurface(Context context, AttributeSet attrs) {
        super(context, attrs);
        SurfaceHolder holder = getHolder();
        holder.addCallback(this);
        setOnTouchListener(this);

        // red
        graphPaint.setColor(0xffff0000);
        // smooth edges
        graphPaint.setAntiAlias(true);
        //lineas
        scaleLinePaint.setColor(0xff000000);
        scaleLinePaint.setStrokeWidth(2);
        //texto
        scaleTextPaint.setColor(Color.BLACK);
        scaleTextPaint.setTextAlign(Paint.Align.LEFT);
        scaleTextPaint.setTextSize((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()));
        //scaleTextPaint.setTextSize(20);
        scaleTextPaint.setStyle(Paint.Style.FILL);
        scaleTextPaint.setTypeface(Typeface.SANS_SERIF);

        erasePaint.setColor(colorBackground);
    }





    private int getColor(int binVal, int binMax, int lastPos) {
        int R,G,B;
        //generar el color del bin con base en el valor del bin y si es uno de los últimos
        //el color base es gris desde el mínimo hasta blanco
        //si es de los últimos se tiñe con rojo subiendo el R hasta donde se pueda y si hace falta baja los otros

        if(binVal==0 && lastPos==0) return 0xff000000;

        final float min = 60;
        R = G = B = (int)Math.min(255, min + (float)binVal * (255f-min) / (float)binMax);
        if(lastPos==1) { //posición actual
            R=255;G=0;B=0;
        }
        if(lastPos>1) {
            lastPos = PosStatsMain.LAST_POS_SHOW_CNT - lastPos + 1; //invertir el valor, que quede LAST_POS_SHOW_CNT si es la última posición
            final int Rsum = (int)(lastPos / (float)PosStatsMain.LAST_POS_SHOW_CNT * 60f) + 10; //queda entre 10 y 70
            R += Rsum;
            if (R > 255) {
                G -= R - 255;
                B -= R - 255;
                R = 255;
            }
        }
        return 0xff000000 + 0x010000 * R + 0x0100 * G + B;

    }

    private double getBinSize() { //esto es double porque hay cálculos que ocupan esto con decimales
        return XYgraphWidth / (double) PosStatsMain.BIN_CNT;
    }

    //0-based indices
    private Rect getBinRect(int x, int y) {
        double binSize = getBinSize();
        return new Rect(XYgraphLeft + (int) (x * binSize), graphTop + (int) (y * binSize), XYgraphLeft + (int) ((x + 1) * binSize), graphTop + (int) ((y + 1) * binSize));
    }

    private Rect getBinRectZ(int z) {
        double binSize = getBinSize();
        return new Rect(ZgraphLeft, graphTop + (int) ((z) * binSize), ZgraphLeft + (int) binSize, graphTop + (int) ((z + 1) * binSize));
    }

    public void draw() throws  Exception{
        fullCanvas=null;
        if(FullredrawNeeded) {
            fullCanvas = getCanvas(null);
            if(fullCanvas==null) return;
            fullCanvas.drawColor(colorBackground);
            ZredrawNeeded = XYredrawNeeded = true;
            FullredrawNeeded = false;
        }

        if(ZredrawNeeded) {
            fullRedrawZ();
            ZredrawNeeded=false;
        }
        else {
            drawZ();
        }
        if(XYredrawNeeded) {
            fullRedrawXY();
            XYredrawNeeded=false;
        }
        else {
            drawXY();
        }

        if(fullCanvas!=null) {
            holder.unlockCanvasAndPost(fullCanvas);
            fullCanvas=null;
        }
    }

    private void drawZ() {
        Canvas canvas = getZgraphCanvas();
        if (canvas == null) return;
        canvas.drawRect(getZgraphRect(),erasePaint);//es necesario para borrar el lado derecho
        double binSize = getBinSize();
        for (int j = 0; j < PosStatsMain.BIN_CNT; j++) {
            graphPaint.setColor(getColor(posStats.binsZ[j], posStats.binZmax, posStats.getLastBinPosZ(j)));
            canvas.drawRect(getBinRectZ(j), graphPaint);
        }
        //dibujar el centro
        int [] centerBins = posStats.getCenterBins();
        if(centerBins!=null) {
            graphPaint.setColor(0xFF33FF33);
            canvas.drawCircle(ZgraphLeft + (float) binSize * 1f, graphTop +(int)(centerBins[1]*binSize), (float) binSize * 0.75f, graphPaint);
        }
        canvasPost(canvas);
    }

    private void drawXY() throws  Exception {

        Canvas canvas = getXYgraphCanvas();

        //dibujar los bins
        double binSize = getBinSize();
        for (int i = 0; i < PosStatsMain.BIN_CNT; i++) {
            for (int j = 0; j < PosStatsMain.BIN_CNT; j++) {
                graphPaint.setColor(getColor(posStats.binsXY[i + j * PosStatsMain.BIN_CNT], posStats.binXYmax, posStats.getLastBinPosXY(i + j * PosStatsMain.BIN_CNT)));
                canvas.drawRect(getBinRect(i, j), graphPaint);
            }
        }
        //dibujar el centro
        int [] centerBins = posStats.getCenterBins();
        if(centerBins!=null) {
            int xbin = centerBins[0] % PosStatsMain.BIN_CNT;
            int cx = XYgraphLeft + (int)((xbin+0.5) * binSize);
            int ybin = (int)Math.floor(centerBins[0] / PosStatsMain.BIN_CNT);
            int cy = graphTop + (int)((ybin+0.5) * binSize);

            graphPaint.setColor(0xFF33FF33);
            canvas.drawCircle(cx,cy,(float)binSize, graphPaint);
        }
        canvasPost(canvas);
    }

    private int ZgraphWidth() { //el ancho es un bin más otro para que quepa el círculo de promedio más otro para la línea de escala
        return (int) (getBinSize() * 2);
    }

    public void fullRedrawZ() {
        Log.i(TAG, "full redraw Z");
        //dibujar la escala es una raya horizontal de 1 bin a una unidad de escala por debajo de la parte de arriba y otra en la parte de arriba y una vertical al borde derecho que las une
        Canvas canvas = getZscaleLineCanvas();
        if (canvas == null) return;
        canvas.drawRect(getZscaleLineRect(),erasePaint);
        float xleft = ZgraphLeft+ ZgraphWidth();
        float xright = (float) (xleft + getBinSize()-3);
        canvas.drawLine(xleft,graphTop,xright,graphTop, scaleLinePaint);
        float ybottom = (float) (graphTop+posStats.getZscaleSizeBins()*getBinSize());
        canvas.drawLine(xright,graphTop, xright, ybottom, scaleLinePaint);
        canvas.drawLine(xleft,ybottom,xright, ybottom, scaleLinePaint);
        canvasPost(canvas);

        //dibujar el texto
        canvas = getZscaleTextCanvas();
        if (canvas == null) return;
        canvas.drawRect(getZscaleTextRect(),erasePaint);
        canvas.drawText(posStats.getZscaleString(),(float) (ZgraphLeft+getBinSize()),graphTop-SCALE_MARGIN_BOTTOM-scaleTextPaint.getFontMetrics().descent,scaleTextPaint);

        canvasPost(canvas);

        drawZ();
    }

    public void fullRedrawXY() throws Exception {
        Log.i(TAG, "full redraw XY");
        //dibujar la escala es una raya desde el borde derecho del gráfico con dos verticales a los lados, la horizoantal encima de las verticales. 1 bin de alto
        Canvas canvas = getXYscaleCanvas();
        if (canvas == null) return;
        canvas.drawRect(getXYscaleRect(),erasePaint);
        float xleft = (float) (XYgraphLeft+XYgraphWidth-(posStats.getXYscaleSizeBins()*getBinSize()));
        float xright = XYgraphLeft+XYgraphWidth-3;
        float ytop = (float) (graphTop - SCALE_MARGIN_BOTTOM - getBinSize());
        float ybottom = (float) (ytop + getBinSize());
        canvas.drawLine(xleft,ytop,xleft,ybottom,scaleLinePaint);
        canvas.drawLine(xright,ytop,xright,ybottom,scaleLinePaint);
        canvas.drawLine(xleft,ytop,xright,ytop,scaleLinePaint);

        //el texto va encima de la línea desde el lado izq
        canvas.drawText(posStats.getXYscaleString(), xleft,ytop - scaleTextPaint.getFontMetrics().descent,scaleTextPaint);
        canvasPost(canvas);

        drawXY();
    }


    private Rect getZgraphRect() {
        return new Rect(ZgraphLeft, graphTop, ZgraphLeft + ZgraphWidth(), graphTop + XYgraphWidth);
    }
    private Rect getXYgraphRect() {
        return new Rect(XYgraphLeft, graphTop, XYgraphLeft + XYgraphWidth, graphTop + XYgraphWidth);
    }
    private Rect getZscaleLineRect() {
        return new Rect(ZgraphLeft+ ZgraphWidth(), graphTop, (int) (ZgraphLeft + ZgraphWidth()+getBinSize()), graphTop + XYgraphWidth);
    }
    private Rect getZscaleTextRect() {
        return new Rect(ZgraphLeft, 0, XYgraphLeft, graphTop-SCALE_MARGIN_BOTTOM);
    }
    private Rect getXYscaleRect() {
        return new Rect(XYgraphLeft, 0, XYgraphLeft+XYgraphWidth, graphTop-SCALE_MARGIN_BOTTOM);
    }

    private Canvas getZgraphCanvas() {
        return getCanvas(getZgraphRect());
    }
    private Canvas getXYgraphCanvas() {
        return getCanvas(getXYgraphRect());
    }
    private Canvas getZscaleLineCanvas() {
        return getCanvas(getZscaleLineRect());
    }
    private Canvas getZscaleTextCanvas() {
        return getCanvas(getZscaleTextRect());
    }
    private Canvas getXYscaleCanvas() {
        return getCanvas(getXYscaleRect());
    }
    private Canvas getCanvas(Rect target) {

        if (holder == null) return null;
        if(fullCanvas!=null) return fullCanvas;
        try {
            if (target == null) return holder.lockCanvas();
            Log.d(TAG, target.toString());
            return holder.lockCanvas(target);
        } catch (Exception e) {
            Log.w(TAG, e.toString());
            return null;
        }

    }

    /**
     * solo sirve cuando el full canvas no está activo
     * @param canvas
     */
    private void canvasPost(Canvas canvas) {
        if(fullCanvas!=null) return;
        holder.unlockCanvasAndPost(canvas);
    }


    public void resetData() {
        posStats.resetData();

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (width == 0 || height == 0) {
            return;
        }
        // resize your UI

        //dejar un margen de 5% a cada lado y entre la barra y el area, el restante 85% es 60% para el area y 25% para la barra
        XYgraphWidth = (int) (width * 0.6);
        XYgraphLeft = (int) (width * 0.35);
        //como el alto es igual al ancho del XY debemos ver si realmente da la altura, si no hay que bajar el ancho
        //se ocupa un 10% de altura adicional entonces lo máximo es un 90% de la altura
        if(XYgraphWidth>height*0.9) XYgraphWidth = (int) (height*0.9);

        //Del espacio que quedara libre vertical es todo encima
        graphTop = (height - XYgraphWidth);  //este top es el mismo para ambos
        ZgraphLeft = (int) (width * 0.05);


        Log.w(TAG, String.format("surfaceChanged w:%d h:%d aw:%d", width, height, XYgraphWidth));

        if (posStats == null) {
            posStats = new PosStatsMain(this);
        }
        FullredrawNeeded = true;


    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        this.holder = holder;

        if (drawThread != null) {
            Log.d(TAG, "draw thread still active..");
            drawingActive = false;
            try {
                drawThread.join();
            } catch (InterruptedException e) { // do nothing
            }
        }

        surfaceReady = true;
        //this.setBackgroundColor(colorBackground); esto no sirve
        //esto sí sirve para que se vea el color de fondo del parent
        this.setZOrderOnTop(true);
        holder.setFormat(PixelFormat.TRANSLUCENT);
        startDrawThread();
        Log.d(TAG, "Created");

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface is not used anymore - stop the drawing thread
        stopDrawThread();
        // and release the surface
        holder.getSurface().release();

        this.holder = null;
        surfaceReady = false;
        Log.d(TAG, "Destroyed");
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        // Handle touch events
        return true;
    }

    public void setZredrawNeeded() {
        ZredrawNeeded=true;
    }
    public void setXYredrawNeeded() {
        XYredrawNeeded=true;
    }

    /**
     * Stops the drawing thread
     */
    public void stopDrawThread() {
        /*if (drawThread == null)
        {
            Log.d(LOGTAG, "DrawThread is null");
            return;
        }
        drawingActive = false;
        while (true)
        {
            try
            {
                Log.d(LOGTAG, "Request last frame");
                drawThread.join(5000);
                break;
            } catch (Exception e)
            {
                Log.e(LOGTAG, "Could not join with draw thread");
            }
        }
        drawThread = null;*/
    }


    /**
     * Creates a new draw thread and starts it.
     */
    public void startDrawThread() {
       /* if (surfaceReady && drawThread == null)
        {
            drawThread = new Thread(this, "Draw thread");
            drawingActive = true;
            drawThread.start();
        }*/
    }

    /*@Override
    public void run()
    {
        Log.d(LOGTAG, "Draw thread started");
        long frameStartTime;
        long frameTime;

        //     * In order to work reliable on Nexus 7, we place ~500ms delay at the start of drawi* (AOSP - Issue 58385)

        if (android.os.Build.BRAND.equalsIgnoreCase("google") && android.os.Build.MANUFACTURER.equalsIgnoreCase("asus") && android.os.Build.MODEL.equalsIgnoreCase("Nexus 7"))
        {
            Log.w(LOGTAG, "Sleep 500ms (Device: Asus Nexus 7)");
            try
            {
                Thread.sleep(500);
            } catch (InterruptedException ignored)
            {
            }
        }
        try
        {
            while (drawingActive)
            {
                if (holder == null)
                {
                    return;
                }

                frameStartTime = System.nanoTime();
                Canvas canvas = holder.lockCanvas();
                if (canvas != null)
                {
                    // clear the screen using black
                    canvas.drawARGB(255, 255, 255, 255);

                    try
                    {
                        // Your drawing here
                        canvas.drawRect(50, 50, getWidth() / 2, getHeight() / 2, samplePaint);
                    } finally
                    {

                        holder.unlockCanvasAndPost(canvas);
                    }
                }

                // calculate the time required to draw the frame in ms
                frameTime = (System.nanoTime() - frameStartTime) / 1000000;

                if (frameTime < MAX_FRAME_TIME) // faster than the max fps - limit the FPS
                {
                    try
                    {
                        Thread.sleep(MAX_FRAME_TIME - frameTime);
                    } catch (InterruptedException e)
                    {
                        // ignore
                    }
                }
            }
        } catch (Exception e)
        {
            Log.w(LOGTAG, "Exception while locking/unlocking");
        }
        Log.d(LOGTAG, "Draw thread finished");
    }*/
}