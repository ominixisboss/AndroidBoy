package com.ominixisboss.androidboy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;

/** Save-state slots 1–9 with their thumbnails and save times, for the save and load dialogs. */
final class SlotAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final Bitmap[] thumbnails = new Bitmap[RomLibrary.STATE_SLOTS];
    private final String[] details = new String[RomLibrary.STATE_SLOTS];

    SlotAdapter(Context context, RomLibrary library, File rom) {
        inflater = LayoutInflater.from(context);
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (int slot = 1; slot <= RomLibrary.STATE_SLOTS; slot++) {
            File state = library.stateFile(rom, slot);
            boolean used = state.isFile();
            details[slot - 1] = used ? format.format(new Date(state.lastModified())) : "Empty";
            // States saved before thumbnails existed simply have none.
            thumbnails[slot - 1] = used ? StateThumbnails.read(library.thumbnailFile(rom, slot)) : null;
        }
    }

    @Override
    public int getCount() {
        return RomLibrary.STATE_SLOTS;
    }

    /** The slot number. */
    @Override
    public Integer getItem(int position) {
        return position + 1;
    }

    @Override
    public long getItemId(int position) {
        return position + 1;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView != null ? convertView : inflater.inflate(R.layout.item_state_slot, parent, false);
        ImageView image = view.findViewById(R.id.slot_thumbnail);
        TextView title = view.findViewById(R.id.slot_title);
        TextView detail = view.findViewById(R.id.slot_detail);
        title.setText("Slot " + (position + 1));
        detail.setText(details[position]);
        Bitmap thumbnail = thumbnails[position];
        if (thumbnail != null) {
            BitmapDrawable drawable = new BitmapDrawable(view.getResources(), thumbnail);
            drawable.setFilterBitmap(false); // Keep the pixels sharp when scaled up.
            image.setImageDrawable(drawable);
        } else {
            image.setImageDrawable(null);
        }
        return view;
    }
}
