package com.mobeta.android.dslv;

import java.util.ArrayList;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.DataSetObservable;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.HeaderViewListAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.AbsListView;

import android.os.Debug;


/**
 * ListView subclass that mediates drag and drop resorting of items.
 * 
 * 
 * @author heycosmo
 *
 */
public class DragSortListView extends ListView {
	
	// Drag states
	public final static int NO_DRAG = 0;
	public final static int SRC_EXP = 1;
	public final static int SRC_ABOVE = 2;
	public final static int SRC_BELOW = 3;
	
	private int mDragState = NO_DRAG;
	
	private ImageView mFloatView;
  private int mFloatBGColor;
	private WindowManager mWindowManager;
	private WindowManager.LayoutParams mWindowParams;
	
	/**
	 * Top of ListView in absolute (full, virtual list) y-coords.
	 */
	private int mSrcTop;
	private int mSrcBottom;
	private int mExpTop;
	private int mExpBottom;
	
	/**
	 * At which position is the item currently being dragged. All drag positions
	 * are absolute list view positions; e.g. if there is one header view, and
	 * mDragPos = 1, then mDragPos points to the first list item after the header.
	 */
	private int mExpDragPos;
	/**
	 * At which position was the item being dragged originally
	 */
	private int mSrcDragPos;
	private int mDragPointX;    // at what x offset inside the item did the user grab it
	private int mDragPointY;    // at what y offset inside the item did the user grab it
	private int mXOffset;  // the difference between screen coordinates and coordinates in this view
	private int mYOffset;  // the difference between screen coordinates and coordinates in this view
	private DragListener mDragListener;
	private DropListener mDropListener;
	private RemoveListener mRemoveListener;
	private int mUpScrollStartY;
	private int mDownScrollStartY;
	private float mDownScrollStartYF;
	private float mUpScrollStartYF;
	private GestureDetector mGestureDetector;
	private static final int FLING = 0;
	private static final int SLIDE = 1;
	private static final int TRASH = 2;
	private int mRemoveMode = -1;
	private Rect mTempRect = new Rect();
  private int[] mTempLoc = new int[2];
	private Bitmap mDragBitmap;
	private final int mTouchSlop;
	private int mItemHeightCollapsed = 1;
	private int mExpandedChildHeight; //state var
  private int mFloatViewHeight;
  private int mFloatViewHeightHalf;
	private Drawable mTrashcan;

  private boolean mShuffleRequested = false;

	private View[] mSampleViewTypes = new View[1];

  private ArrayList<Integer> mHeaderHeights = new ArrayList<Integer>();
  private ArrayList<Integer> mFooterHeights = new ArrayList<Integer>();
  private int mHeadersTotalHeight = 0;
  private int mFootersTotalHeight = 0;
	
	private DragScroller mDragScroller;
	private float mDragUpScrollBoundFrac = 1.0f / 3.0f;
	private float mDragDownScrollBoundFrac = 1.0f / 3.0f;
  private float mDragUpScrollHeight;
	private float mDragDownScrollHeight;
	
	private float mMaxScrollSpeed = 0.2f; // pixels per millisec
	
	private DragScrollProfile mScrollProfile = new DragScrollProfile() {
		@Override
		public float getSpeed(float w, long t) {
			return mMaxScrollSpeed * w;
		}
	};
	
	private int mLastX;
	private int mLastY;
	private int mDownY;
	
	private AdapterWrapper mAdapterWrapper;

	public DragSortListView(Context context, AttributeSet attrs) {
		super(context, attrs);
		mRemoveMode = FLING;
		mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

    if (attrs != null) {
      TypedArray a = getContext().obtainStyledAttributes(attrs,
        R.styleable.DragSortListView, 0, 0);

      mItemHeightCollapsed = a.getDimensionPixelSize(
        R.styleable.DragSortListView_collapsed_height, mItemHeightCollapsed);

      mFloatBGColor = a.getColor(R.styleable.DragSortListView_float_background,
        0x00000000);

      mRemoveMode = a.getInt(R.styleable.DragSortListView_remove_mode, -1);

      float frac = a.getFloat(R.styleable.DragSortListView_scroll_bound,
          mDragUpScrollBoundFrac);
      setDragScrollBounds(frac);

	    mMaxScrollSpeed = a.getFloat(
        R.styleable.DragSortListView_max_scroll_speed, mMaxScrollSpeed);

      a.recycle();
    }

    //Log.d("mobeta", "collapsed height=" + mItemHeightCollapsed);

		mDragScroller = new DragScroller();
		setOnScrollListener(mDragScroller);
	}
	
	/**
	 * Set maximum drag scroll speed in positions/second. Only applies
	 * if using default ScrollSpeedProfile.
	 * 
	 * @param max Maximum scroll speed.
	 */
	public void setMaxScrollSpeed(float max) {
		mMaxScrollSpeed = max;
	}
	
	@Override
	public void setAdapter(ListAdapter adapter) {
		//mAdapterWrapper = new AdapterWrapper(adapter);
		mAdapterWrapper = new AdapterWrapper(null, null, adapter);
		
		super.setAdapter(mAdapterWrapper);
	}
	
  /**
   * As opposed to {@link ListView#getAdapter()}, which returns
   * a heavily wrapped ListAdapter (DragSortListView wraps the
   * input ListAdapter {\emph and} ListView wraps the wrapped one).
   *
   * @return The ListAdapter set as the argument of {@link setAdapter()}
   */
	public ListAdapter getInputAdapter() {
		if (mAdapterWrapper == null) {
			return null;
		} else {
			return mAdapterWrapper.getAdapter();
		}
	}

	
	private class AdapterWrapper extends HeaderViewListAdapter {
		private ListAdapter mAdapter;
		
		public AdapterWrapper(ArrayList<FixedViewInfo> headerViewInfos,
				ArrayList<FixedViewInfo> footerViewInfos, ListAdapter adapter) {
			super(headerViewInfos, footerViewInfos, adapter);
			mAdapter = adapter;
		}
		
		public ListAdapter getAdapter() {
			return mAdapter;
		}

		
		@Override
		public View getView(int position, View convertView, ViewGroup parent) {

      RelativeLayout v;
      View child;

      //Log.d("mobeta", "getView: position="+position+" convertView="+convertView);
      if (convertView != null) {

        v = (RelativeLayout) convertView;
        View oldChild = v.getChildAt(0);

        child = super.getView(position, oldChild, v);
        if (child != oldChild) {
          // shouldn't get here if user is reusing convertViews properly
          v.removeViewAt(0);
          v.addView(child);
          // check that tags are equal too?
          v.setTag(child.findViewById(R.id.drag));
        }

      } else {
        AbsListView.LayoutParams params =
          new AbsListView.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        v = new RelativeLayout(getContext());
        v.setLayoutParams(params);
				child = super.getView(position, null, v);
        v.addView(child);

        v.setTag(child.findViewById(R.id.drag));
      }


			ViewGroup.LayoutParams lp = v.getLayoutParams();
			final int numHeaders = getHeaderViewsCount();

      final int srcAdapter = mSrcDragPos - numHeaders;
      final int expAdapter = mExpDragPos - numHeaders;

      boolean itemIsNormal = position != srcAdapter && position != expAdapter;
      boolean listHasExpPos = mDragState == SRC_ABOVE || mDragState == SRC_BELOW;
      boolean itemNeedsWC = itemIsNormal || !listHasExpPos;


      int oldHeight = lp.height;
      if (itemNeedsWC && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
				// items that have a user-provided height
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
      } else if (listHasExpPos) {
				if (position == srcAdapter && lp.height != mItemHeightCollapsed) {
					// collapsed items
        	lp.height = mItemHeightCollapsed;
				} else if (position == expAdapter) {
					// what if a previously-expanded wrapper view is used
					// as a convertView for a different expanded item? 
        	// Always measure child
        	int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        	child.measure(spec, spec);

					mExpandedChildHeight = child.getMeasuredHeight();
					int height = mExpandedChildHeight + mFloatViewHeight;
					if (lp.height != height) {
						lp.height = height;
					}

					// set gravity
					if (mDragState == SRC_ABOVE) {
						v.setGravity(Gravity.TOP);
					} else {
						v.setGravity(Gravity.BOTTOM);
					}
				}
			}

      if (lp.height != oldHeight) {
        v.setLayoutParams(lp);
      }


			int oldVis = v.getVisibility();
			int vis = oldVis;

			if (position == srcAdapter && mDragState != NO_DRAG && vis == View.VISIBLE) {
				vis = View.INVISIBLE;
			} else if (vis == View.INVISIBLE) {
				vis = View.VISIBLE;
			}

			if (vis != oldVis) {
				v.setVisibility(vis);
			}

			return v;
		}
		
	}

  private int getPositionHeight(int position) {
    final ListAdapter adapter = getAdapter();

    final int first = getFirstVisiblePosition();
    final int last = getLastVisiblePosition();

    if (position >= first && position <= last) {
      return getChildAt(position - first).getHeight();
    } else {

      int type = adapter.getItemViewType(position);
			
			// There might be a better place for checking for the following
		  final int typeCount = adapter.getViewTypeCount();
			if (typeCount != mSampleViewTypes.length) {
				mSampleViewTypes = new View[typeCount];
			}

      View v;
      if (type >= 0) {
        if (mSampleViewTypes[type] == null) {
          v = adapter.getView(position, null, this);
          mSampleViewTypes[type] = v;
        } else {
          v = adapter.getView(position, mSampleViewTypes[type], this);
        }
      } else {
        // type is HEADER_OR_FOOTER or IGNORE
        v = adapter.getView(position, null, this);
      }

      ViewGroup.LayoutParams lp = v.getLayoutParams();
      final int height = lp == null ? 0 : lp.height;
      if (height > 0) {
        return height;
      } else {
        int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        v.measure(spec, spec);
        return v.getMeasuredHeight();
      }
        
    }

  }



  /**
   * Get the position of the floating item for any view of the list
   * in its current drag state. Result is restricted to positions
   * between header and footer items.
   *
   * @param y y-coord of dragging finger
   * @param position ListView position
   * @param top y-coord of top of item at given position
   */
  private int getFloatPosition(int y, int position, int top) {

		// get midpoint of floating view (constrained to ListView bounds)
		final int floatViewMidY = Math.max(mFloatViewHeightHalf + getPaddingTop(),
      Math.min(getHeight() - getPaddingBottom() - mFloatViewHeightHalf,
        y - mDragPointY + mFloatViewHeightHalf));

    int floatPos = position;

    int itemTop = top;
    int itemBottom = top;


    // go up only if touch y is above requested top
    if (floatViewMidY < top && position > 0) {
      for (int i = position - 1; i >= 0; --i) {
        itemBottom = itemTop;
        itemTop -= getPositionHeight(i) + getDividerHeight();

        if (floatViewMidY >= itemTop || i == 0) {
          floatPos = i;
          break;
        }
      }
    } else {
      final int count = getCount();
      for (int i = position; i < count; ++i) {
        itemTop = itemBottom;
        itemBottom += getPositionHeight(i) + getDividerHeight();

        if (floatViewMidY < itemBottom || i == count - 1) {
          floatPos = i;
          break;
        }
      }
    }

    //Log.d("mobeta", "  getFloatPos: pre correct="+floatPos);

		final int numHeaders = getHeaderViewsCount();
		final int numFooters = getFooterViewsCount();
		
		if (floatPos < numHeaders) {
			return numHeaders;
		} else if (floatPos >= getCount() - numFooters) {
			return getCount() - numFooters - 1;
		}


    // fix float position when between src and expanded positions
    switch (mDragState) {
      case SRC_ABOVE: //expanded up
        if (floatPos == mExpDragPos) {
          if (floatViewMidY < itemTop + mFloatViewHeight) {
            //Log.d("mobeta", "float in upper part of expanded item");
            floatPos -= 1;
          }
        } else if (floatPos < mExpDragPos && floatPos > mSrcDragPos) {
          floatPos -= 1;
        }
        break;
      case SRC_BELOW:
        if (floatPos == mExpDragPos) {
          if (floatViewMidY >= itemBottom - mFloatViewHeight) {
            floatPos += 1;
          }
        } else if (floatPos > mExpDragPos && floatPos < mSrcDragPos) {
          floatPos += 1;
        }
        break;
      case SRC_EXP:
      case NO_DRAG:
      default:
    }
    //Log.d("mobeta", "  getFloatPos: post correct="+floatPos);

    return floatPos;

  }

	

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		if (mRemoveListener != null && mGestureDetector == null) {
			if (mRemoveMode == FLING) {
				mGestureDetector = new GestureDetector(getContext(), new SimpleOnGestureListener() {
					@Override
					public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
							float velocityY) {
						if (mFloatView != null) {
							if (velocityX > 1000) {
								Rect r = mTempRect;
								mFloatView.getDrawingRect(r);
								if ( e2.getX() > r.right * 2 / 3) {
									// fast fling right with release near the right edge of the screen
									dropFloatView(true);
								}
							}
							// flinging while dragging should have no effect
							// i.e. the gesture should not pass on to other
							// onTouch handlers. Gobble...
							return true;
						}
						return false;
					}
				});
			}
		}
		if (mDragListener != null || mDropListener != null) {
			switch (ev.getAction()) {
			case MotionEvent.ACTION_DOWN:
				//Log.d("mobeta", "action down!");
				int x = (int) ev.getX();
				int y = (int) ev.getY();
				mLastX = x;
				mLastY = y;
				mDownY = y;
				int itemnum = pointToPosition(x, y); //includes headers/footers
				
				final int numHeaders = getHeaderViewsCount();
				final int numFooters = getFooterViewsCount();
				
				//Log.d("mobeta", "touch down on position " + itemnum);
				if (itemnum == AdapterView.INVALID_POSITION || itemnum < numHeaders || itemnum >= getCount() - numFooters) {
					break;
				}
				ViewGroup item = (ViewGroup) getChildAt(itemnum - getFirstVisiblePosition());
				
				mDragPointX = x - item.getLeft();
				mDragPointY = y - item.getTop();
        final int rawX = (int) ev.getRawX();
        final int rawY = (int) ev.getRawY();
				mXOffset = rawX - x;
				mYOffset = rawY - y;


        View dragBox = (View) item.getTag();
        boolean dragHit = false;
        if (dragBox != null) {
          dragBox.getLocationOnScreen(mTempLoc);
          
          dragHit = rawX > mTempLoc[0] && rawY > mTempLoc[1] &&
                    rawX < mTempLoc[0] + dragBox.getWidth() &&
                    rawY < mTempLoc[1] + dragBox.getHeight();
        }

        if (dragHit) {
					item.setDrawingCacheEnabled(true);
					// Create a copy of the drawing cache so that it does not get recycled
					// by the framework when the list tries to clean up memory
					Bitmap bitmap = Bitmap.createBitmap(item.getDrawingCache());
					item.setDrawingCacheEnabled(false);

          mFloatViewHeight = item.getHeight();
          mFloatViewHeightHalf = mFloatViewHeight / 2;
					
					mExpDragPos = itemnum;
					mSrcDragPos = itemnum;
					
					//Log.d("mobeta", "getCount() = " + getCount());
					//Log.d("mobeta", "headers = " + getHeaderViewsCount());
					
					startDragging(bitmap, x, y);

          // cancel ListView fling
          MotionEvent ev2 = MotionEvent.obtain(ev);
          ev2.setAction(MotionEvent.ACTION_CANCEL);
          super.onInterceptTouchEvent(ev2);

					//return false;
					return true;
				}
				removeFloatView();
				break;
			}
		}
		return super.onInterceptTouchEvent(ev);
	}
	
  /**
   * Set the width of each drag scroll region by specifying
   * a fraction of the ListView height.
   *
   * @param heightFraction Fraction of ListView height. Capped at
   * 0.5f.
   * 
   */
	public void setDragScrollBounds(float heightFraction) {
    setDragScrollBounds(heightFraction, heightFraction);
	}
	
	
  /**
   * Set the width of each drag scroll region by specifying
   * a fraction of the ListView height.
   *
   * @param upperFrac Fraction of ListView height for up-scroll bound.
   * Capped at 0.5f.
   * @param lowerFrac Fraction of ListView height for down-scroll bound.
   * Capped at 0.5f.
   * 
   */
	public void setDragScrollBounds(float upperFrac, float lowerFrac) {
    if (lowerFrac > 0.5f) {
      mDragDownScrollBoundFrac = 0.5f;
    } else {
      mDragDownScrollBoundFrac = lowerFrac;
    }

    if (upperFrac > 0.5f) {
      mDragUpScrollBoundFrac = 0.5f;
    } else {
      mDragUpScrollBoundFrac = upperFrac;
    }

    if (getHeight() != 0) {
      updateScrollBounds();
    }
  }

  private void updateScrollBounds() {
    final int padTop = getPaddingTop();
		final int listHeight = getHeight() - padTop - getPaddingBottom();
    float heightF = (float) listHeight;
		
    mUpScrollStartYF = padTop + mDragUpScrollBoundFrac * heightF;
    mDownScrollStartYF = padTop + (1.0f - mDragDownScrollBoundFrac) * heightF;

		mUpScrollStartY = (int) mUpScrollStartYF;
		mDownScrollStartY = (int) mDownScrollStartYF;
    Log.d("mobeta", "up start="+mUpScrollStartY);
    Log.d("mobeta", "down start="+mDownScrollStartY);
		
		mDragUpScrollHeight = mUpScrollStartYF - padTop;
		mDragDownScrollHeight = padTop + listHeight - mDownScrollStartYF;
  }
	
	
  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    updateScrollBounds();
  }

	private int getViewHeight(View v) {

    // measure to get height of header item
    ViewGroup.LayoutParams lp = v.getLayoutParams();
    final int height = lp == null ? 0 : lp.height;
    if (height > 0) {
			return height;
    } else {
      int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
      v.measure(spec, spec);
			return v.getMeasuredHeight();
    }

	}

  @Override
  public void addHeaderView(View v, Object data, boolean isSelectable) {
    super.addHeaderView(v, data, isSelectable);
		
		mHeaderHeights.add(getViewHeight(v));

    mHeadersTotalHeight += mHeaderHeights.get(mHeaderHeights.size() - 1);
  }


  @Override
  public void addFooterView(View v, Object data, boolean isSelectable) {
    super.addFooterView(v, data, isSelectable);

		mFooterHeights.add(getViewHeight(v));

    mFootersTotalHeight += mFooterHeights.get(mFooterHeights.size() - 1);
  }


	private void dropFloatView(boolean removeSrcItem) {
		mDragScroller.stopScrolling(true);
		
		if (removeSrcItem) {
			if (mRemoveListener != null) {
				mRemoveListener.remove(mSrcDragPos - getHeaderViewsCount());
			}
		} else {
			if (mDropListener != null && mExpDragPos >= 0 && mExpDragPos < getCount()) {
				final int numHeaders = getHeaderViewsCount();
				mDropListener.drop(mSrcDragPos - numHeaders, mExpDragPos - numHeaders);
			}

			//Log.d("mobeta", "unexpand views called");
			int top = getChildAt(0).getTop();
			int firstPos = getFirstVisiblePosition();

			View expView = getChildAt(mExpDragPos - firstPos);
			if (expView != null) {
				ViewGroup.LayoutParams lp = expView.getLayoutParams();
				lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
				expView.requestLayout();
			}

			//Log.d("mobeta", "last pos=" + (getCount() - 1) + ", last vis pos=" + getLastVisiblePosition());
			//Log.d("mobeta", "src pos > last pos = " + (mSrcDragPos > getLastVisiblePosition()));

			if (mSrcDragPos < firstPos) {
				// collapsed src item is off screen, no need to expand it; but, we
				// must adjust the scroll accordingly
				setSelectionFromTop(firstPos - 1, top - getPaddingTop());
			} else if (mSrcDragPos <= getLastVisiblePosition()) {
				// collapsed src item is in view, expand it
				View srcView = getChildAt(mSrcDragPos - firstPos);
				ViewGroup.LayoutParams lp = srcView.getLayoutParams();
				lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
				srcView.requestLayout();
				
				srcView.setVisibility(View.VISIBLE);
			}
		}
		
		removeFloatView();
		
		mDragState = NO_DRAG;
	}


	
	
	/**
	 * Call this when list items are shuffled while dragging.
	 */
	private void updateListState() {
		if (mFloatView == null) {
			mDragState = NO_DRAG;
			return;
		} else if (mExpDragPos == mSrcDragPos) {
			mDragState = SRC_EXP;
		} else if (mSrcDragPos < mExpDragPos) {
			mDragState = SRC_ABOVE;
		} else {
			mDragState = SRC_BELOW;
		}
		
	}
	
	

	/**
   * Smart item expansion.
   */
	private void expandItem(int position) {
		RelativeLayout v = (RelativeLayout) getChildAt(position - getFirstVisiblePosition());
		if (v != null && mFloatView != null) {
      //Log.d("mobeta", "  expanding item "+position);
			ViewGroup.LayoutParams lp = v.getLayoutParams();

      int oldHeight = lp.height;
			if (lp.height == mItemHeightCollapsed && position == mSrcDragPos) {
				// expanding collapsed src item
				lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
			} else if (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT && position != mExpDragPos) {
				// expanding normal item
				lp.height = v.getHeight() + mFloatViewHeight;
				
				// must set gravity in this case
				if (position > mSrcDragPos) {
					v.setGravity(Gravity.TOP);
				} else {
					v.setGravity(Gravity.BOTTOM);
				}
			} else {
        Log.d("mobeta", "expand item skipped");
      }

      if (lp.height != oldHeight) {
			  v.requestLayout();
      }
		}

	}

	/**
   * Smart item collapstion. Usually (Always?)
	 * called with mExpDragPos as arg.
   */
	private void collapseItem(int position) {
		View v = getChildAt(position - getFirstVisiblePosition());
		if (v != null) {
			ViewGroup.LayoutParams lp = v.getLayoutParams();

			//if (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
				// collapsing normal item
      int oldHeight = lp.height;
			if (position == mSrcDragPos) {
        // collapsing source item
				lp.height = mItemHeightCollapsed;
			} else if (position == mExpDragPos) {
				// to save time, assume collapsing an expanded item
				lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
			} else {
        Log.d("mobeta", "collapse ignored, pos="+position);
      }

      if (lp.height != oldHeight) {
			  v.requestLayout();
      }
		}

	}
	
	/**
	 * Shuffle list items given new float position.
	 * 
	 * @param floatPos 
	 */
	private void shuffleItems(int floatPos) {
		
		//Log.d("mobeta", "float position: " + floatPos);
		//Log.d("mobeta", "exp position: " + mExpDragPos);
		//Log.d("mobeta", "first position: " + getFirstVisiblePosition() + " height: " + getChildAt(0).getHeight());

		if (floatPos != mExpDragPos) {
			// if we get here, the ListView is inconsistent with the
			// floating view

      //Log.d("mobeta", "collapsing="+mExpDragPos+" expanding="+floatPos);
			collapseItem(mExpDragPos);
			expandItem(floatPos);
			
			// callback
			if (mDragListener != null) {
				final int numHeaders = getHeaderViewsCount();
				mDragListener.drag(mExpDragPos - numHeaders, floatPos - numHeaders);
			}
			
			// update state
			mExpDragPos = floatPos;
			updateListState();

      mShuffleRequested = true;
		}
	}
	
  @Override
  protected void layoutChildren() {
    super.layoutChildren();

    if (mShuffleRequested) {
      mShuffleRequested = false;
    }
  }

	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		if (mGestureDetector != null) {
			mGestureDetector.onTouchEvent(ev);
		}
		if ((mDragListener != null || mDropListener != null) && mFloatView != null) {
			int action = ev.getAction(); 
			switch (action & MotionEvent.ACTION_MASK) {
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				Rect r = mTempRect;
				mFloatView.getDrawingRect(r);
				//mDragScroller.stopScrolling(true);
				
				if (mRemoveMode == SLIDE && ev.getX() > r.right * 3 / 4) {
					dropFloatView(true);
				} else {
					dropFloatView(false);
				}
				
				break;

			case MotionEvent.ACTION_DOWN:
				//doExpansion();
				break;
			case MotionEvent.ACTION_MOVE:
				int x = (int) ev.getX();
				int y = (int) ev.getY();
				
				// make src item invisible on first move away from pickup
				// point. Reduces flicker.
				if (mLastY == mDownY) {
          // should we be this careful? shuffleItems is!
          final View item = getChildAt(mSrcDragPos - getFirstVisiblePosition());
          if (item != null) {
            item.setVisibility(INVISIBLE);
          }
					//getChildAt(mSrcDragPos - getFirstVisiblePosition()).setVisibility(INVISIBLE);
				}
				
				dragView(x, y);

        if (!mShuffleRequested) {
				  final int first = getFirstVisiblePosition();
				  int centerPos = first + getChildCount() / 2;
				  int centerTop = getChildAt(centerPos - first).getTop();
				  
          //Log.d("mobeta", "move shuffle");
				  shuffleItems(getFloatPosition(y, centerPos, centerTop));
        }
				
        //Log.d("mobeta", "down scroll start="+mDownScrollStartY);
        //Log.d("mobeta", "up scroll start="+mUpScrollStartY);
				// start or stop drag scrolling
				if (y > mDownScrollStartY) {

					if (mLastY <= mDownScrollStartY) {
						if (mDragScroller.isScrolling()) {
							// moved directly from upscroll to down scroll
							mDragScroller.stopScrolling(true);
						}
						//Log.d("mobeta", "start drag scrolling down");
						// just entered lower drag-scroll region
						mLastY = y;
						mDragScroller.startScrolling(DragScroller.DOWN);
					}
				} else if (y < mUpScrollStartY) {
					if (mLastY >= mUpScrollStartY) {
						// just entered upper drag-scroll region
						if (mDragScroller.isScrolling()) {
							// moved directly from down-scroll to up-scroll
							mDragScroller.stopScrolling(true);
						}
						mLastY = y;
						mDragScroller.startScrolling(DragScroller.UP);
					}
				} else if (mLastY > mDownScrollStartY || mLastY < mUpScrollStartY) {
					mDragScroller.stopScrolling(false);
				}
				
				mLastX = x;
				mLastY = y;
				break;
			}
			return true;
		}
		return super.onTouchEvent(ev);
	}

	private void startDragging(Bitmap bm, int x, int y) {
		//removeFloatView();

		mWindowParams = new WindowManager.LayoutParams();
		mWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
		mWindowParams.x = x - mDragPointX + mXOffset;
		mWindowParams.y = y - mDragPointY + mYOffset;

    //Log.d("mobeta", "float view x=" + mWindowParams.x + " y=" + mWindowParams.y);

		mWindowParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
		mWindowParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
		mWindowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
		| WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
		| WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
		| WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
		| WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
		mWindowParams.format = PixelFormat.TRANSLUCENT;
		mWindowParams.windowAnimations = 0;

		Context context = getContext();
		ImageView v = new ImageView(context);
		//int backGroundColor = context.getResources().getColor(R.color.dragndrop_background);
		//v.setBackgroundColor(mFloatBGColor);
		//v.setBackgroundResource(R.drawable.playlist_tile_drag);
		v.setPadding(0, 0, 0, 0);
		v.setImageBitmap(bm);
		mDragBitmap = bm;

		mWindowManager = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
		mWindowManager.addView(v, mWindowParams);
		mFloatView = v;
		
		mDragState = SRC_EXP;
	}

	private void dragView(int x, int y) {
    //Log.d("mobeta", "float view pure x=" + x + " y=" + y);
		if (mRemoveMode == SLIDE) {
			float alpha = 1.0f;
			int width = mFloatView.getWidth();
			if (x > width / 2) {
				alpha = ((float)(width - x)) / (width / 2);
			}
			mWindowParams.alpha = alpha;
		}

		if (mRemoveMode == FLING || mRemoveMode == TRASH) {
			mWindowParams.x = x - mDragPointX + mXOffset;
		} else {
			mWindowParams.x = 0;
		}
		
		
		// keep floating view from going past bottom of last header view
		final int numHeaders = getHeaderViewsCount();
		final int numFooters = getFooterViewsCount();
		final int firstPos = getFirstVisiblePosition();
		final int lastPos = getLastVisiblePosition();
    //Log.d("mobeta", "nHead="+numHeaders+" nFoot="+numFooters+" first="+firstPos+" last="+lastPos);
		int limit = getPaddingTop();
		if (firstPos < numHeaders) {
			limit = getChildAt(numHeaders - firstPos - 1).getBottom();
		}
		int footerLimit = getHeight() - getPaddingBottom();
		if (lastPos >= getCount() - numFooters) {
			// get top-most footer view
      footerLimit = getChildAt(getCount() - numFooters - firstPos).getTop();
			//footerLimit = getChildAt(getChildCount() - 1 - lastPos + getCount() - numFooters).getTop();
		}
		
		//Log.d("mobeta", "dragView top=" + (y - mDragPointY));
		//Log.d("mobeta", "limit=" + limit);
    //Log.d("mobeta", "mDragPointY=" + mDragPointY);
		if (y - mDragPointY < limit) {
			mWindowParams.y = mYOffset + limit;
		} else if (y - mDragPointY + mFloatViewHeight > footerLimit) {
			mWindowParams.y = mYOffset + footerLimit - mFloatViewHeight;
		} else {
			mWindowParams.y = y - mDragPointY + mYOffset;
		}
    //Log.d("mobeta", "float view taint x=" + mWindowParams.x + " y=" + mWindowParams.y);
		mWindowManager.updateViewLayout(mFloatView, mWindowParams);

		if (mTrashcan != null) {
			int width = mFloatView.getWidth();
			if (y > getHeight() * 3 / 4) {
				mTrashcan.setLevel(2);
			} else if (width > 0 && x > width / 4) {
				mTrashcan.setLevel(1);
			} else {
				mTrashcan.setLevel(0);
			}
		}
	}

	
	private void removeFloatView() {
		
		if (mFloatView != null) {
			mFloatView.setVisibility(GONE);
			WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
			wm.removeView(mFloatView);
			mFloatView.setImageDrawable(null);
			mFloatView = null;
		}
		if (mDragBitmap != null) {
			mDragBitmap.recycle();
			mDragBitmap = null;
		}
		if (mTrashcan != null) {
			mTrashcan.setLevel(0);
		}
	}

	public void setTrashcan(Drawable trash) {
		mTrashcan = trash;
		mRemoveMode = TRASH;
	}

	public void setDragListener(DragListener l) {
		mDragListener = l;
	}

	/**
	 * This better reorder your ListAdapter! DragSortListView does not do this
	 * for you; doesn't make sense to. Make sure
	 * {@link BaseAdapter#notifyDataSetChanged()} or something like it is
	 * called in your implementation.
	 * 
	 * @param l
	 */
	public void setDropListener(DropListener l) {
		mDropListener = l;
	}

	/**
	 * Probably a no-brainer, but make sure that your remove listener
	 * calls {@link BaseAdapter#notifyDataSetChanged()} or something like it.
	 * When an item removal occurs, DragSortListView
	 * relies on a redraw of all the items to recover invisible views
	 * and such. Strictly speaking, if you remove something, your dataset
	 * has changed...
	 * 
	 * @param l
	 */
	public void setRemoveListener(RemoveListener l) {
		mRemoveListener = l;
	}

	public interface DragListener {
		void drag(int from, int to);
	}
	
	/**
	 * Your implementation of this has to reorder your ListAdapter! 
	 * Make sure to call
	 * {@link BaseAdapter#notifyDataSetChanged()} or something like it
	 * in your implementation.
	 * 
	 * @author heycosmo
	 *
	 */
	public interface DropListener {
		void drop(int from, int to);
	}
	
	/**
	 * Make sure to call
	 * {@link BaseAdapter#notifyDataSetChanged()} or something like it
	 * in your implementation.
	 * 
	 * @author heycosmo
	 *
	 */
	public interface RemoveListener {
		void remove(int which);
	}
	
	/**
	 * Completely custom scroll speed profile. Default increases linearly
	 * with position and is constant in time. Create your own by implementing
	 * {@link DragSortListView.DragScrollProfile}.
	 * 
	 * @param ssp
	 */
	public void setDragScrollProfile(DragScrollProfile ssp) {
		if (ssp != null) {
			mScrollProfile = ssp;
		}
	}
	
	/**
	 * Interface for controlling
	 * scroll speed as a function of touch position and time. Use
	 * {@link DragSortListView#setDragScrollProfile(DragScrollProfile)} to
	 * set custom profile.
	 * 
	 * @author heycosmo
	 *
	 */
	public interface DragScrollProfile {
		/**
		 * Return a scroll speed in pixels/millisecond. Always return a
		 * positive number.
		 * 
		 * @param w Normalized position in scroll region (i.e. w \in [0,1]).
		 * Small w typically means slow scrolling.
		 * @param t Time (in milliseconds) since start of scroll (handy if you
		 * want scroll acceleration).
		 * @return Scroll speed at position w and time t in pixels/ms.
		 */
		float getSpeed(float w, long t);
	}
	
	private class DragScroller implements Runnable, AbsListView.OnScrollListener {

		private boolean mAbort;
		
		private long mPrevTime;
		
		private int dy;
		private float dt;
		private long tStart;
		private int scrollDir;
		
		public final static int UP = 0;
		public final static int DOWN = 1;
		
		private float mScrollSpeed; // pixels per ms
		
		private boolean mScrolling = false;
		
		private int mLastHeader;
		private int mFirstFooter;
		
		public boolean isScrolling() {
			return mScrolling;
		}
		
		public void startScrolling(int dir) {
			if (!mScrolling) {
        //Debug.startMethodTracing("dslv-scroll");

				mAbort = false;
				mScrolling = true;
				tStart = SystemClock.uptimeMillis();
				mPrevTime = tStart;
				mLastHeader = getHeaderViewsCount() - 1;
				mFirstFooter = getCount() - getFooterViewsCount();
				scrollDir = dir;
				post(this);
			}
		}
		
		public void stopScrolling(boolean now) {
			if (now) {
				DragSortListView.this.removeCallbacks(this);
				mScrolling = false;
			} else {
				mAbort = true;
			}

      //Debug.stopMethodTracing();
		}
		
		
		@Override
		public void run() {
			if (mAbort) {
				mScrolling = false;
				return;
			}
			
			if (scrollDir == UP) {
				mScrollSpeed = mScrollProfile.getSpeed((mUpScrollStartYF - mLastY) / mDragUpScrollHeight, mPrevTime);
			} else {
				mScrollSpeed = -mScrollProfile.getSpeed((mLastY - mDownScrollStartYF) / mDragDownScrollHeight, mPrevTime);
			}
			
			dt = SystemClock.uptimeMillis() - mPrevTime;
      // dy is change in View position of a list item; i.e. positive dy
      // means user is scrolling up (list item moves down the screen, remember
      // y=0 is at top of View).
			dy = (int) Math.round(mScrollSpeed * dt);

      //Log.d("mobeta", "scrolling by " + dy);
      // don't bail if dy=0, touch might be at edge of scroll region; but,
      // don't do any work 
      if (dy == 0) {
			  mPrevTime += dt;
        post(this);
        return;
      }

      final int first = getFirstVisiblePosition();
      final int last = getLastVisiblePosition();

      final int count = getCount();

      final int listHeight = getHeight() - getPaddingTop() - getPaddingBottom();

      int movePosition;
      if (dy > 0) {
        if (first == 0 && getChildAt(0).getTop() == 0) {
          return;
        }
        movePosition = first;
        dy = Math.min(listHeight, dy);
      } else {
        if (last == count - 1 &&
            getChildAt(last - first).getBottom() <= listHeight) {
          return;
        }
        movePosition = last;
        dy = Math.max(-listHeight, dy);
      }
      // what if movePosition is a position to be expanded?

      final int oldTop = getChildAt(movePosition - first).getTop();
      int newTop = oldTop + dy;
      //Log.d("mobeta", "movePos="+movePosition+" newTop="+newTop+" oldTop="+(newTop-dy)+" lvheight="+getHeight()+" fvheight="+mFloatViewHeight+" oldBottom="+getChildAt(movePosition-first).getBottom());
			
      if (!mShuffleRequested) {
			  // Where will floating view end up given current list state?
			  //int newFloatPos = getFloatPosition(mLastX, mLastY, viewTop);
			  int newFloatPos = getFloatPosition(mLastY, movePosition, newTop);
			  
			  //if (newFloatPos <= mLastHeader || newFloatPos >= mFirstFooter) {
			  //	dragView(mLastX, mLastY);
			  //}
        if (newFloatPos >= mSrcDragPos && newFloatPos != mExpDragPos) {
          if (newFloatPos == movePosition && scrollDir == DragScroller.DOWN) {
            // adjust scroll distance for item expansion
            newTop -= mFloatViewHeight;
          } else if (newFloatPos < movePosition && scrollDir == DragScroller.UP) {
            // adjust scroll distance for item collapse
            newTop += mFloatViewHeight;
          }
        }
			  
			  // Schedule expand/collapse where needed and update list state.
			  // Important that this goes before the following underscroll move.
			  shuffleItems(newFloatPos);
      }
			
			// Do underscroll (assumes/uses new list state)
			//int pos = listYToPosition(viewTop);
			//int itemTop = getItemTop(pos) - viewTop;
			//Log.d("mobeta", "viewTop=" + viewTop + " pos=" + pos + " itemTop=" + itemTop);
      //Log.d("mobeta", "dy="+(newTop - oldTop));
			setSelectionFromTop(movePosition, newTop - getPaddingTop());
			
			mPrevTime += dt;
			
			post(this);
			
		}

		@Override
		public void onScroll(AbsListView view, int firstVisibleItem,
				int visibleItemCount, int totalItemCount) {

			if (mScrolling && visibleItemCount != 0) {
				// Keep floating view from overlapping header and footer
				// items during scrolling
				if (firstVisibleItem <= mLastHeader) {
					int dragViewTop = mLastY - mDragPointY;
					int lastHeaderBottom = getChildAt(mLastHeader - firstVisibleItem).getBottom();
					if (dragViewTop < lastHeaderBottom) {
						mWindowParams.y = mYOffset + lastHeaderBottom;
						mWindowManager.updateViewLayout(mFloatView, mWindowParams);
					}
				} else if (firstVisibleItem + visibleItemCount > mFirstFooter) {
					int dragViewBottom = mLastY - mDragPointY + mFloatViewHeight;
					int firstFooterTop = getChildAt(mFirstFooter - firstVisibleItem).getTop();
					if (dragViewBottom > firstFooterTop) {
						mWindowParams.y = mYOffset + firstFooterTop - mFloatViewHeight;
						mWindowManager.updateViewLayout(mFloatView, mWindowParams);
					}
				}
			}
		}

		@Override
		public void onScrollStateChanged(AbsListView view, int scrollState) {}

	}

}
