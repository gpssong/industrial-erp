<template>
  <view v-if="show" class="rc-mask" @touchmove.stop.prevent>
    <view class="rc-head">
      <view class="rc-title">框选要识别的区域</view>
      <view class="rc-sub">拖方框内部移动，拖右下角改大小；不框选就按整张识别</view>
    </view>

    <view class="rc-stage" id="rcStage">
      <image :src="src" mode="widthFix" class="rc-img" @load="onImgLoad" />
      <view
        class="rc-box"
        :style="boxStyle"
        @touchstart="onTouchStart('move', $event)"
        @touchmove.stop.prevent="onTouchMove"
        @touchend="onTouchEnd"
      >
        <view class="rc-grid"><view class="rc-line v1"></view><view class="rc-line v2"></view><view class="rc-line h1"></view></view>
        <view
          class="rc-handle"
          @touchstart.stop="onTouchStart('resize', $event)"
          @touchmove.stop.prevent="onTouchMove"
          @touchend="onTouchEnd"
        ></view>
      </view>
    </view>

    <view class="rc-foot">
      <view class="rc-btn" @click="onCancel">取消</view>
      <view class="rc-btn" @click="onReset">重置</view>
      <view class="rc-btn primary" @click="onConfirm">{{ isFull ? '整张识别' : '识别这区域' }}</view>
    </view>
  </view>
</template>

<script>
import { clampRect, isFullFrame, MIN_EDGE_RATIO } from '../utils/ocrCrop.js'

const FULL = { x: 0, y: 0, w: 1, h: 1 }

export default {
  name: 'RegionCropper',
  props: {
    show: { type: Boolean, default: false },
    // uni.chooseImage 拿到的本地临时图路径 (不能传服务端 URL, 见 utils/ocrCrop.js 头注释)
    src: { type: String, default: '' }
  },
  emits: ['confirm', 'cancel'],
  data() {
    return {
      rect: { ...FULL },
      stage: null,
      drag: null
    }
  },
  computed: {
    boxStyle() {
      const r = this.rect
      return {
        left: r.x * 100 + '%',
        top: r.y * 100 + '%',
        width: r.w * 100 + '%',
        height: r.h * 100 + '%'
      }
    },
    isFull() {
      return isFullFrame(this.rect)
    }
  },
  watch: {
    show(val) {
      if (val) {
        this.rect = { ...FULL }
        this.$nextTick(() => this.measure())
      }
    }
  },
  methods: {
    measure() {
      return new Promise((resolve) => {
        if (typeof uni === 'undefined' || typeof uni.createSelectorQuery !== 'function') {
          resolve(null)
          return
        }
        uni
          .createSelectorQuery()
          .in(this)
          .select('#rcStage')
          .boundingClientRect((r) => {
            this.stage = r && r.width ? r : null
            resolve(this.stage)
          })
          .exec()
      })
    },
    onImgLoad() {
      // widthFix 下图片加载完 stage 高度才定下来
      this.measure()
    },
    point(e) {
      const t = (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0]) || {}
      return { x: t.clientX !== undefined ? t.clientX : t.pageX || 0, y: t.clientY !== undefined ? t.clientY : t.pageY || 0 }
    },
    async onTouchStart(mode, e) {
      if (!this.stage) await this.measure()
      if (!this.stage) return
      const p = this.point(e)
      this.drag = { mode, startX: p.x, startY: p.y, from: { ...this.rect } }
    },
    onTouchMove(e) {
      if (!this.drag || !this.stage) return
      const p = this.point(e)
      const dx = (p.x - this.drag.startX) / this.stage.width
      const dy = (p.y - this.drag.startY) / this.stage.height
      const f = this.drag.from
      if (this.drag.mode === 'move') {
        this.rect = clampRect({
          x: Math.min(Math.max(f.x + dx, 0), 1 - f.w),
          y: Math.min(Math.max(f.y + dy, 0), 1 - f.h),
          w: f.w,
          h: f.h
        })
      } else {
        this.rect = clampRect({
          x: f.x,
          y: f.y,
          w: Math.min(Math.max(f.w + dx, MIN_EDGE_RATIO), 1 - f.x),
          h: Math.min(Math.max(f.h + dy, MIN_EDGE_RATIO), 1 - f.y)
        })
      }
    },
    onTouchEnd() {
      this.drag = null
    },
    onReset() {
      this.rect = { ...FULL }
    },
    async onConfirm() {
      if (!this.stage) await this.measure()
      this.$emit('confirm', this.isFull ? null : { ...this.rect })
    },
    onCancel() {
      this.$emit('cancel')
    }
  }
}
</script>

<style scoped>
.rc-mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  /* v1.1.179: uni-app H5 的 uni-tabbar 默认 z-index=998，原来 900 会被它压在下面，
     原料入库/扫码类 tab 页全屏框选时底部「识别这区域/取消/重置」被 tabBar 盖住点不到。
     提到 9999 与项目其它全屏遮罩 (users/product-add 等) 对齐，确保盖过 tabBar。 */
  z-index: 9999;
  background: #10151c;
  display: flex;
  flex-direction: column;
}
.rc-head {
  padding: 24rpx 28rpx 12rpx;
}
.rc-title {
  color: #fff;
  font-size: 30rpx;
  font-weight: 600;
}
.rc-sub {
  color: #9fb0c0;
  font-size: 22rpx;
  margin-top: 6rpx;
}
.rc-stage {
  flex: 1;
  position: relative;
  overflow: hidden;
  margin: 0 20rpx;
}
.rc-img {
  display: block;
  width: 100%;
}
.rc-box {
  position: absolute;
  border: 2rpx solid #2f7de1;
  box-sizing: border-box;
  box-shadow: 0 0 0 2000px rgba(8, 12, 18, 0.62);
  background: transparent;
}
.rc-grid {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  pointer-events: none;
}
.rc-line {
  position: absolute;
  background: rgba(255, 255, 255, 0.35);
}
.rc-line.v1 { left: 33.33%; top: 0; bottom: 0; width: 1px; }
.rc-line.v2 { left: 66.66%; top: 0; bottom: 0; width: 1px; }
.rc-line.h1 { top: 50%; left: 0; right: 0; height: 1px; }
.rc-handle {
  position: absolute;
  right: -18rpx;
  bottom: -18rpx;
  width: 56rpx;
  height: 56rpx;
  border-radius: 50%;
  background: #2f7de1;
  border: 4rpx solid #fff;
  box-sizing: border-box;
}
.rc-foot {
  display: flex;
  align-items: center;
  gap: 18rpx;
  padding: 20rpx 28rpx calc(24rpx + env(safe-area-inset-bottom));
}
.rc-btn {
  flex: 1;
  height: 78rpx;
  line-height: 78rpx;
  text-align: center;
  border-radius: 12rpx;
  font-size: 27rpx;
  color: #d8e2ec;
  background: #232c37;
}
.rc-btn.primary {
  flex: 1.6;
  color: #fff;
  background: #2f7de1;
}
</style>
