package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * The row of title cards Up opens above a dock app (ContentShelf's items): Left and Right move through them while
 * the backdrop and the shelf's title follow, OK opens the title, and the host decides what Up, Down and Back do.
 */
final class ContentRow extends RecyclerView.Adapter<ContentRow.Holder> {

    interface Host {
        void onCardFocused(int index);

        void onCardChosen(int index);

        /** Up, Down or Back on a card; true when the host handled it. */
        boolean onCardKey(int index, int keyCode);

        /** The item's picture if it is in memory; null shows the card's name on its plain slab until it arrives. */
        Bitmap imageFor(ContentShelf.Item item);
    }

    static final float CARD_H_DP = 126f;
    private static final float FOCUS_SCALE = 1.08f;

    private final Host host;
    private final boolean animations;
    private final List<ContentShelf.Item> items = new ArrayList<>();

    ContentRow(Host host, boolean animations) {
        this.host = host;
        this.animations = animations;
    }

    void setItems(List<ContentShelf.Item> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    /** Puts pictures that have arrived on the cards on screen, without a rebind that would move focus. */
    void refreshImages(RecyclerView row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = row.getChildViewHolder(row.getChildAt(i));
            int pos = vh.getBindingAdapterPosition();
            if (vh instanceof Holder && pos >= 0 && pos < items.size()) ((Holder) vh).showImage(items.get(pos));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_content_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ContentShelf.Item item = items.get(position);
        h.label.setText(item.title);
        h.showImage(item);
        boolean focused = h.card.isFocused();
        h.card.setScaleX(focused && animations ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused && animations ? FOCUS_SCALE : 1f);
    }

    final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView image;
        final TextView label;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.contentCard);
            image = v.findViewById(R.id.contentImage);
            label = v.findViewById(R.id.contentLabel);
            card.setClipToOutline(true);
            card.setOnFocusChangeListener((view, has) -> {
                float s = has && animations ? FOCUS_SCALE : 1f;
                if (animations) view.animate().scaleX(s).scaleY(s).setDuration(160).start();
                else {
                    view.setScaleX(s);
                    view.setScaleY(s);
                }
                int pos = getBindingAdapterPosition();
                if (has && pos != RecyclerView.NO_POSITION) host.onCardFocused(pos);
            });
            card.setOnClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) host.onCardChosen(pos);
            });
            card.setOnKeyListener((view, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_ESCAPE:
                        return host.onCardKey(pos, code);
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return pos == 0;                    // the row's ends hold focus
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return pos == items.size() - 1;
                    default:
                        return false;
                }
            });
        }

        void showImage(ContentShelf.Item item) {
            // Until the picture arrives the card is its name on the plain slab; the name stays on the picture after.
            image.setImageBitmap(host.imageFor(item));
        }
    }
}
