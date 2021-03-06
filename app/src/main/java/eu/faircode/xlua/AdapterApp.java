/*
    This file is part of XPrivacy/Lua.

    XPrivacy/Lua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacy/Lua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacy/Lua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2018 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.support.v7.util.DiffUtil;
import android.support.v7.util.ListUpdateCallback;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdapterApp extends RecyclerView.Adapter<AdapterApp.ViewHolder> implements Filterable {
    private static final String TAG = "XLua.App";

    private int iconSize;

    private boolean showAll = false;
    private CharSequence query = null;
    private List<XHook> hooks;
    private List<XApp> all = new ArrayList<>();
    private List<XApp> filtered = new ArrayList<>();
    private Map<String, Boolean> expanded = new HashMap<>();

    private ExecutorService executor = Executors.newCachedThreadPool();

    public class ViewHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener, View.OnLongClickListener, CompoundButton.OnCheckedChangeListener, XApp.IListener {
        XApp app;

        View itemView;
        ImageView ivExpander;
        ImageView ivIcon;
        TextView tvLabel;
        TextView tvUid;
        TextView tvPackage;
        ImageView ivPersistent;
        CheckBox cbAssigned;
        RecyclerView rvGroup;

        AdapterGroup adapter;

        ViewHolder(View itemView) {
            super(itemView);

            this.itemView = itemView;
            ivExpander = itemView.findViewById(R.id.ivExpander);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvLabel = itemView.findViewById(R.id.tvLabel);
            tvUid = itemView.findViewById(R.id.tvUid);
            tvPackage = itemView.findViewById(R.id.tvPackage);
            ivPersistent = itemView.findViewById(R.id.ivPersistent);
            cbAssigned = itemView.findViewById(R.id.cbAssigned);

            rvGroup = itemView.findViewById(R.id.rvGroup);
            rvGroup.setHasFixedSize(true);
            LinearLayoutManager llm = new LinearLayoutManager(itemView.getContext());
            llm.setAutoMeasureEnabled(true);
            rvGroup.setLayoutManager(llm);
            adapter = new AdapterGroup();
            rvGroup.setAdapter(adapter);
        }

        private void wire() {
            ivExpander.setOnClickListener(this);
            ivIcon.setOnClickListener(this);
            tvLabel.setOnClickListener(this);
            tvUid.setOnClickListener(this);
            tvPackage.setOnClickListener(this);

            ivIcon.setOnLongClickListener(this);
            tvLabel.setOnLongClickListener(this);
            tvUid.setOnLongClickListener(this);
            tvPackage.setOnLongClickListener(this);

            cbAssigned.setOnCheckedChangeListener(this);
        }

        private void unwire() {
            ivExpander.setOnClickListener(null);
            ivIcon.setOnClickListener(null);
            tvLabel.setOnClickListener(null);
            tvUid.setOnClickListener(null);
            tvPackage.setOnClickListener(null);

            ivIcon.setOnLongClickListener(null);
            tvLabel.setOnLongClickListener(null);
            tvUid.setOnLongClickListener(null);
            tvPackage.setOnLongClickListener(null);

            cbAssigned.setOnCheckedChangeListener(null);
        }

        @Override
        public void onClick(View view) {
            int id = view.getId();
            if (id == R.id.ivExpander ||
                    id == R.id.ivIcon || id == R.id.tvLabel ||
                    id == R.id.tvUid || id == R.id.tvPackage) {
                if (!expanded.containsKey(app.packageName))
                    expanded.put(app.packageName, false);
                expanded.put(app.packageName, !expanded.get(app.packageName));
                updateExpand();
            }
        }

        @Override
        public boolean onLongClick(View view) {
            Intent intent = view.getContext().getPackageManager().getLaunchIntentForPackage(app.packageName);
            if (intent != null)
                view.getContext().startActivity(intent);
            return (intent != null);
        }

        @Override
        public void onCheckedChanged(final CompoundButton compoundButton, final boolean checked) {
            Log.i(TAG, "Check changed");
            int id = compoundButton.getId();
            if (id == R.id.cbAssigned) {
                if (checked) {
                    for (XHook hook : hooks)
                        app.assignments.add(new XAssignment(hook));
                } else
                    app.assignments.clear();

                adapter.set(app, hooks);

                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        ArrayList<String> hookids = new ArrayList<>();
                        for (XHook hook : hooks)
                            hookids.add(hook.getId());

                        Bundle args = new Bundle();
                        args.putStringArrayList("hooks", hookids);
                        args.putString("packageName", app.packageName);
                        args.putInt("uid", app.uid);
                        args.putBoolean("delete", !checked);
                        args.putBoolean("kill", !app.persistent);
                        compoundButton.getContext().getContentResolver()
                                .call(XSettings.URI, "xlua", "assignHooks", args);
                    }
                });
            }
        }

        @Override
        public void onChange() {
            Log.i(TAG, "Group changed");
            notifyItemChanged(getAdapterPosition());
        }

        void updateExpand() {
            boolean isExpanded = (expanded.containsKey(app.packageName) && expanded.get(app.packageName));
            ivExpander.setImageLevel(isExpanded ? 1 : 0);
            rvGroup.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        }
    }

    AdapterApp(Context context) {
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.listPreferredItemHeight, typedValue, true);
        int height = TypedValue.complexToDimensionPixelSize(typedValue.data, context.getResources().getDisplayMetrics());
        iconSize = Math.round(height * context.getResources().getDisplayMetrics().density + 0.5f);

        setHasStableIds(true);
    }

    void set(boolean showAll, String query, List<XHook> hooks, List<XApp> apps) {
        Log.i(TAG, "Set all=" + showAll + " query=" + query + " hooks=" + hooks.size() + " apps=" + apps.size());
        this.showAll = showAll;
        this.query = query;
        this.hooks = hooks;

        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.SECONDARY); // Case insensitive, process accents etc

        Collections.sort(apps, new Comparator<XApp>() {
            @Override
            public int compare(XApp app1, XApp app2) {
                return collator.compare(app1.label, app2.label);
            }
        });

        all.clear();
        all.addAll(apps);

        getFilter().filter(query);
    }

    void setShowAll(boolean value) {
        if (showAll != value) {
            showAll = value;
            getFilter().filter(query);
        }
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            private boolean expanded1 = false;

            @Override
            protected FilterResults performFiltering(CharSequence query) {
                AdapterApp.this.query = query;

                List<XApp> visible = new ArrayList<>();
                if (showAll || !TextUtils.isEmpty(query))
                    visible.addAll(all);
                else
                    for (XApp app : all)
                        if (app.uid > Process.FIRST_APPLICATION_UID && app.icon > 0 && app.enabled)
                            visible.add(app);

                List<XApp> results = new ArrayList<>();
                if (TextUtils.isEmpty(query))
                    results.addAll(visible);
                else {
                    query = query.toString().toLowerCase().trim();
                    int uid;
                    try {
                        uid = Integer.parseInt(query.toString());
                    } catch (NumberFormatException ignore) {
                        uid = -1;
                    }

                    for (XApp app : visible)
                        if (app.uid == uid ||
                                app.packageName.toLowerCase().contains(query) ||
                                (app.label != null && app.label.toLowerCase().contains(query)))
                            results.add(app);
                }

                if (results.size() == 1) {
                    String packageName = results.get(0).packageName;
                    if (!expanded.containsKey(packageName)) {
                        expanded1 = true;
                        expanded.put(packageName, true);
                    }
                }

                FilterResults filterResults = new FilterResults();
                filterResults.values = results;
                filterResults.count = results.size();
                return filterResults;
            }

            @Override
            protected void publishResults(CharSequence query, FilterResults result) {
                final List<XApp> apps = (result.values == null
                        ? new ArrayList<XApp>()
                        : (List<XApp>) result.values);
                Log.i(TAG, "Filtered apps count=" + apps.size());

                DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new AppDiffCallback(expanded1, filtered, apps));

                filtered.clear();
                filtered.addAll(apps);
                notifyDataSetChanged();

                diff.dispatchUpdatesTo(AdapterApp.this);

                diff.dispatchUpdatesTo(new ListUpdateCallback() {
                    @Override
                    public void onInserted(int position, int count) {
                        Log.i(TAG, "Inserted " + count + " @" + position);
                    }

                    @Override
                    public void onRemoved(int position, int count) {
                        Log.i(TAG, "Removed " + count + " @" + position);
                    }

                    @Override
                    public void onMoved(int fromPosition, int toPosition) {
                        Log.i(TAG, "Moved from" + fromPosition + " to " + toPosition);
                    }

                    @Override
                    public void onChanged(int position, int count, Object payload) {
                        Log.i(TAG, "Changed " + count + " @" + position);
                    }
                });
            }
        };
    }

    private class AppDiffCallback extends DiffUtil.Callback {
        private boolean expanded1;
        private List<XApp> prev;
        private List<XApp> next;

        AppDiffCallback(boolean expanded1, List<XApp> prev, List<XApp> next) {
            this.expanded1 = expanded1;
            this.prev = prev;
            this.next = next;
        }

        @Override
        public int getOldListSize() {
            return prev.size();
        }

        @Override
        public int getNewListSize() {
            return next.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return (!expanded1 &&
                    prev.get(oldItemPosition).packageName.equals(next.get(newItemPosition).packageName));
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            XApp app1 = prev.get(oldItemPosition);
            XApp app2 = next.get(newItemPosition);

            if (!app1.packageName.equals(app2.packageName) ||
                    app1.uid != app2.uid ||
                    app1.icon != app2.icon ||
                    !app1.label.equals(app2.label) ||
                    app1.enabled != app2.enabled ||
                    app1.persistent != app2.persistent ||
                    app1.assignments.size() != app2.assignments.size())
                return false;

            for (XAssignment a1 : app1.assignments) {
                int i2 = app2.assignments.indexOf(a1); // by hookid
                if (i2 < 0)
                    return false;
                XAssignment a2 = app2.assignments.get(i2);
                if (a1.installed != a2.installed ||
                        a1.used != a2.used ||
                        a1.restricted != a2.restricted)
                    return false;
            }

            return true;
        }
    }

    @Override
    public long getItemId(int position) {
        XApp assigment = filtered.get(position);
        return assigment.packageName.hashCode() << 32 | assigment.uid;
    }

    @Override
    public int getItemCount() {
        return filtered.size();
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.app, parent, false));
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, int position) {
        holder.unwire();
        holder.app = filtered.get(position);
        holder.app.setListener(holder);

        // App icon
        if (holder.app.icon <= 0)
            holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        else {
            Uri uri = Uri.parse("android.resource://" + holder.app.packageName + "/" + holder.app.icon);
            GlideApp.with(holder.itemView.getContext())
                    .load(uri)
                    .override(iconSize, iconSize)
                    .into(holder.ivIcon);
        }

        // App info
        holder.tvLabel.setText(holder.app.label);
        holder.tvUid.setText(Integer.toString(holder.app.uid));
        holder.tvPackage.setText(holder.app.packageName);
        holder.ivPersistent.setVisibility(holder.app.persistent ? View.VISIBLE : View.GONE);

        // Assignment info
        holder.cbAssigned.setChecked(holder.app.assignments.size() > 0);
        holder.cbAssigned.setEnabled(
                holder.app.assignments.size() == 0 || holder.app.assignments.size() == hooks.size());
        holder.adapter.set(holder.app, hooks);

        holder.updateExpand();

        holder.wire();
    }

    @Override
    public void onViewRecycled(ViewHolder holder) {
        holder.unwire();
    }
}
