package com.sisant.android.gnss;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SignalsBarAdapter extends RecyclerView.Adapter<SignalsBarAdapter.SignalViewHolder> {

    private static final int MIN_SIGNAL_POWER = 0;
    private static final int MAX_SIGNAL_POWER = 60;

    private static final Map<String, Integer> CONSTELLATION_ORDER;
    static {
        Map<String, Integer> order = new HashMap<String, Integer>();
        order.put("GPS", 0);
        order.put("GLONASS", 1);
        order.put("GALILEO", 2);
        order.put("BEIDOU", 3);
        order.put("UNKNOWN", 4);
        CONSTELLATION_ORDER = Collections.unmodifiableMap(order);
    }

    private final List<DisplaySignal> signals = new ArrayList<DisplaySignal>();

    static class DisplaySignal {
        final String constellation;
        final int satelliteId;
        final int signalPower;

        DisplaySignal(String constellation, int satelliteId, int signalPower) {
            this.constellation = constellation;
            this.satelliteId = satelliteId;
            this.signalPower = signalPower;
        }
    }

    static class SignalViewHolder extends RecyclerView.ViewHolder {
        final View signalBar;
        final FrameLayout barArea;
        final TextView signalPowerLabel;

        SignalViewHolder(@NonNull View itemView) {
            super(itemView);
            signalBar = itemView.findViewById(R.id.signalBar);
            barArea = itemView.findViewById(R.id.barArea);
            signalPowerLabel = itemView.findViewById(R.id.signalPowerLabel);
        }
    }

    public boolean submitSignals(List<NMEAParser.SatelliteSignal> sourceSignals) {
        List<DisplaySignal> filtered = new ArrayList<DisplaySignal>();
        Set<String> constellations = new HashSet<String>();
        if (sourceSignals != null) {
            for (NMEAParser.SatelliteSignal sat : sourceSignals) {
                if (sat == null || sat.signalPowerByBand == null) {
                    continue;
                }
                // Regla de producto: si existe L1 usar L1; si no existe usar L2.
                Integer chosen = sat.signalPowerByBand.get("L1");
                if (chosen == null) {
                    chosen = sat.signalPowerByBand.get("L2");
                }
                if (chosen == null) {
                    continue; // satélite sin información de potencia se oculta
                }
                int power = Math.max(MIN_SIGNAL_POWER, Math.min(MAX_SIGNAL_POWER, chosen));
                if (power <= 0) {
                    continue;
                }
                filtered.add(new DisplaySignal(sat.constellation, sat.satelliteId, power));
                constellations.add(sat.constellation);
            }
        }

        if (filtered.size() < 5 || constellations.size() <= 1) {
            return false;
        }

        Collections.sort(filtered, new Comparator<DisplaySignal>() {
            @Override
            public int compare(DisplaySignal a, DisplaySignal b) {
                int constellationCompare = Integer.compare(orderForConstellation(a.constellation), orderForConstellation(b.constellation));
                if (constellationCompare != 0) return constellationCompare;
                return Integer.compare(a.satelliteId, b.satelliteId);
            }
        });

        signals.clear();
        signals.addAll(filtered);
        notifyDataSetChanged();
        return true;
    }

    @NonNull
    @Override
    public SignalViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_signal_bar, parent, false);
        return new SignalViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SignalViewHolder holder, int position) {
        DisplaySignal signal = signals.get(position);
        holder.signalBar.setBackgroundColor(colorForConstellation(signal.constellation));
        holder.signalPowerLabel.setText(String.valueOf(signal.signalPower));

        int barAreaHeight = holder.barArea.getHeight();
        if (barAreaHeight <= 0) {
            barAreaHeight = (int) (140 * holder.itemView.getResources().getDisplayMetrics().density);
        }
        int targetHeight = (int) ((signal.signalPower / (float) MAX_SIGNAL_POWER) * barAreaHeight);
        targetHeight = Math.max((int) (2 * holder.itemView.getResources().getDisplayMetrics().density), targetHeight);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) holder.signalBar.getLayoutParams();
        lp.height = targetHeight;
        lp.gravity = android.view.Gravity.BOTTOM;
        holder.signalBar.setLayoutParams(lp);
    }

    @Override
    public int getItemCount() {
        return signals.size();
    }

    public boolean isEmpty() {
        return signals.isEmpty();
    }

    private static int orderForConstellation(String constellation) {
        Integer order = CONSTELLATION_ORDER.get(constellation);
        return order == null ? CONSTELLATION_ORDER.get("UNKNOWN") : order;
    }

    private static int colorForConstellation(String constellation) {
        if ("GPS".equals(constellation)) return Color.parseColor("#1976D2");
        if ("GLONASS".equals(constellation)) return Color.parseColor("#D32F2F");
        if ("GALILEO".equals(constellation)) return Color.parseColor("#388E3C");
        if ("BEIDOU".equals(constellation)) return Color.parseColor("#F57C00");
        return Color.parseColor("#616161");
    }
}
