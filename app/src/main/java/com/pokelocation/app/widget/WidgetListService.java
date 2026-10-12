package com.pokelocation.app.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.Collections;
import java.util.List;

/** Lista do widget só para API &lt; 31 (no 31+ o provider usa RemoteCollectionItems). Lê só o cache JSON, sem banco. */
public class WidgetListService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(),
                intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID));
    }

    private static final class Factory implements RemoteViewsFactory {
        private final Context c;
        private final int id;
        private List<WidgetData.Item> items = Collections.emptyList();
        private int mode;

        Factory(Context c, int id) {
            this.c = c;
            this.id = id;
        }

        @Override public void onCreate() {}

        @Override
        public void onDataSetChanged() {
            items = WidgetData.visible(c, id, WidgetData.cached(c, id));
            mode = QuickActionsWidgetProvider.themeMode(c, id);
        }

        @Override public void onDestroy() { items = Collections.emptyList(); }

        @Override public int getCount() { return items.size(); }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= items.size()) return null;
            return QuickActionsWidgetProvider.row(c, items.get(position), mode);
        }

        @Override public RemoteViews getLoadingView() { return null; }

        @Override public int getViewTypeCount() { return 1; }

        @Override
        public long getItemId(int position) {
            return position < items.size() ? QuickActionsWidgetProvider.stableId(items.get(position)) : position;
        }

        @Override public boolean hasStableIds() { return true; }
    }
}
