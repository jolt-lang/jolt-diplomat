//! Diplomat bridge for SDL3 + SDL3_ttf.
//! One SdlApp opaque owns the full SDL context (window, canvas, events, font).

#[diplomat::bridge]
#[diplomat::abi_rename = "sdl3_{0}_mv1"]
mod ffi {
    use sdl3::event::Event;
    use sdl3::pixels::Color;
    use sdl3::render::{FPoint, FRect};

    pub struct SdlEvent {
        pub kind: u8,
        pub key_code: i32,
        pub mouse_button: u8,
        pub mouse_x: f32,
        pub mouse_y: f32,
    }

    #[diplomat::opaque_mut]
    pub struct SdlApp {
        _sdl: sdl3::Sdl,
        canvas: sdl3::render::Canvas<sdl3::video::Window>,
        events: sdl3::EventPump,
        font: Option<*mut sdl3_ttf_sys::ttf::TTF_Font>,
    }

    #[diplomat::opaque]
    pub struct SdlError(String);

    impl SdlError {
        pub fn message(&self, write: &mut diplomat_runtime::DiplomatWrite) {
            use std::fmt::Write as _;
            let _ = write.write_str(&self.0);
        }
    }

    impl SdlApp {
        pub fn create(title: &str, width: u32, height: u32) -> Result<Box<SdlApp>, Box<SdlError>> {
            let sdl = sdl3::init().map_err(|e| Box::new(SdlError(e.to_string())))?;
            let video = sdl.video().map_err(|e| Box::new(SdlError(e.to_string())))?;
            let window = video
                .window(title, width, height)
                .position_centered()
                .build()
                .map_err(|e| Box::new(SdlError(e.to_string())))?;
            let canvas = window.into_canvas();
            let events = sdl.event_pump().map_err(|e| Box::new(SdlError(e.to_string())))?;
            unsafe { sdl3_ttf_sys::ttf::TTF_Init(); }
            Ok(Box::new(SdlApp { _sdl: sdl, canvas, events, font: None }))
        }

        /// Load a TTF font from path at given point size. Call once before draw_text.
        pub fn load_font(&mut self, path: &str, pt_size: u32) -> Result<(), Box<SdlError>> {
            let cpath = std::ffi::CString::new(path)
                .map_err(|e| Box::new(SdlError(e.to_string())))?;
            let font = unsafe {
                sdl3_ttf_sys::ttf::TTF_OpenFont(cpath.as_ptr(), pt_size as f32)
            };
            if font.is_null() {
                return Err(Box::new(SdlError("TTF_OpenFont failed".into())));
            }
            if let Some(old) = self.font.take() {
                unsafe { sdl3_ttf_sys::ttf::TTF_CloseFont(old); }
            }
            self.font = Some(font);
            Ok(())
        }

        /// Draw text at (x, y) with given RGBA color using the loaded font.
        pub fn draw_text(&mut self, text: &str, x: f32, y: f32, r: u8, g: u8, b: u8, a: u8) {
            use sdl3::sys::{pixels::SDL_Color, render::*, surface::SDL_DestroySurface};
            let font = match self.font {
                Some(f) => f,
                None => return,
            };
            let ctext = match std::ffi::CString::new(text) {
                Ok(s) => s,
                Err(_) => return,
            };
            let color = SDL_Color { r, g, b, a };
            unsafe {
                let surface = sdl3_ttf_sys::ttf::TTF_RenderText_Blended(
                    font, ctext.as_ptr(), 0, color,
                );
                if surface.is_null() { return; }

                let raw_renderer = self.canvas.raw();
                let texture = SDL_CreateTextureFromSurface(raw_renderer, surface);
                if texture.is_null() {
                    SDL_DestroySurface(surface);
                    return;
                }

                let w = (*surface).w as f32;
                let h = (*surface).h as f32;
                SDL_DestroySurface(surface);

                let dst = sdl3::sys::rect::SDL_FRect { x, y, w, h };
                SDL_RenderTexture(raw_renderer, texture, std::ptr::null(), &dst);
                SDL_DestroyTexture(texture);
            }
        }

        pub fn poll_event(&mut self) -> SdlEvent {
            match self.events.poll_event() {
                None => SdlEvent { kind: 0, key_code: 0, mouse_button: 0, mouse_x: 0.0, mouse_y: 0.0 },
                Some(Event::Quit { .. }) => SdlEvent { kind: 1, key_code: 0, mouse_button: 0, mouse_x: 0.0, mouse_y: 0.0 },
                Some(Event::KeyDown { keycode, .. }) => SdlEvent {
                    kind: 2,
                    key_code: keycode.map(|k| k as i32).unwrap_or(0),
                    mouse_button: 0, mouse_x: 0.0, mouse_y: 0.0,
                },
                Some(Event::KeyUp { keycode, .. }) => SdlEvent {
                    kind: 3,
                    key_code: keycode.map(|k| k as i32).unwrap_or(0),
                    mouse_button: 0, mouse_x: 0.0, mouse_y: 0.0,
                },
                Some(Event::MouseMotion { x, y, .. }) => SdlEvent {
                    kind: 4, key_code: 0, mouse_button: 0, mouse_x: x, mouse_y: y,
                },
                Some(Event::MouseButtonDown { mouse_btn, x, y, .. }) => SdlEvent {
                    kind: 5, key_code: 0, mouse_button: mouse_btn as u8, mouse_x: x, mouse_y: y,
                },
                Some(Event::MouseButtonUp { mouse_btn, x, y, .. }) => SdlEvent {
                    kind: 6, key_code: 0, mouse_button: mouse_btn as u8, mouse_x: x, mouse_y: y,
                },
                Some(_) => SdlEvent { kind: 0, key_code: 0, mouse_button: 0, mouse_x: 0.0, mouse_y: 0.0 },
            }
        }

        pub fn set_draw_color(&mut self, r: u8, g: u8, b: u8, a: u8) {
            self.canvas.set_draw_color(Color::RGBA(r, g, b, a));
        }

        pub fn clear(&mut self) { self.canvas.clear(); }
        pub fn present(&mut self) { self.canvas.present(); }

        pub fn fill_rect(&mut self, x: f32, y: f32, w: f32, h: f32) {
            let _ = self.canvas.fill_rect(FRect::new(x, y, w, h));
        }

        pub fn draw_rect(&mut self, x: f32, y: f32, w: f32, h: f32) {
            let _ = self.canvas.draw_rect(FRect::new(x, y, w, h));
        }

        pub fn draw_line(&mut self, x1: f32, y1: f32, x2: f32, y2: f32) {
            let _ = self.canvas.draw_line(FPoint::new(x1, y1), FPoint::new(x2, y2));
        }

        pub fn set_title(&mut self, title: &str) -> Result<(), Box<SdlError>> {
            self.canvas.window_mut().set_title(title)
                .map_err(|e| Box::new(SdlError(e.to_string())))
        }

        pub fn window_width(&self) -> u32 { self.canvas.window().size().0 }
        pub fn window_height(&self) -> u32 { self.canvas.window().size().1 }
    }
}

unsafe impl Send for ffi::SdlApp {}

// ── Audio stream ─────────────────────────────────────────────────────────────
// Uses SDL3's push-audio API: Jolt fills f32 samples and calls put_samples()
// each frame. No callback thread needed.
#[diplomat::bridge]
#[diplomat::abi_rename = "sdl3_{0}_mv1"]
mod audio_ffi {
    #[diplomat::opaque]
    pub struct AudioStream {
        dev: u32,
        stream: *mut sdl3::sys::audio::SDL_AudioStream,
    }

    #[diplomat::opaque]
    pub struct AudioError(String);

    impl AudioError {
        pub fn message(&self, write: &mut diplomat_runtime::DiplomatWrite) {
            use std::fmt::Write as _;
            let _ = write.write_str(&self.0);
        }
    }

    impl AudioStream {
        /// Open default audio device, stereo f32 at given sample rate.
        pub fn open(sample_rate: i32) -> Result<Box<AudioStream>, Box<AudioError>> {
            use sdl3::sys::audio::*;
            use sdl3::sys::init::{SDL_InitSubSystem, SDL_INIT_AUDIO};
            unsafe {
                SDL_InitSubSystem(SDL_INIT_AUDIO);
                let spec = SDL_AudioSpec {
                    format: SDL_AUDIO_F32LE,
                    channels: 2,
                    freq: sample_rate,
                };
                // SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK = 0xFFFFFFFF
                let dev = SDL_OpenAudioDevice(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec);
                if dev.0 == 0 {
                    return Err(Box::new(AudioError("SDL_OpenAudioDevice failed".into())));
                }
                let stream = SDL_CreateAudioStream(&spec, &spec);
                if stream.is_null() {
                    return Err(Box::new(AudioError("SDL_CreateAudioStream failed".into())));
                }
                let dev_id = sdl3::sys::audio::SDL_AudioDeviceID(dev.0);
                SDL_BindAudioStream(dev_id, stream);
                SDL_ResumeAudioDevice(dev_id);
                Ok(Box::new(AudioStream { dev: dev.0, stream }))
            }
        }

        /// Bytes currently queued in the audio stream.
        pub fn queued_bytes(&self) -> i32 {
            unsafe { sdl3::sys::audio::SDL_GetAudioStreamQueued(self.stream) }
        }

        /// Push interleaved stereo f32 samples. len = number of floats (frames*2).
        pub fn put_samples(&self, samples: &[f32]) {
            use sdl3::sys::audio::SDL_PutAudioStreamData;
            unsafe {
                let bytes = samples.len() * std::mem::size_of::<f32>();
                SDL_PutAudioStreamData(
                    self.stream,
                    samples.as_ptr() as *const _,
                    bytes as i32,
                );
            }
        }
    }

    impl Drop for AudioStream {
        fn drop(&mut self) {
            use sdl3::sys::audio::*;
            unsafe {
                SDL_DestroyAudioStream(self.stream);
                SDL_CloseAudioDevice(SDL_AudioDeviceID(self.dev));
            }
        }
    }
}

unsafe impl Send for audio_ffi::AudioStream {}
