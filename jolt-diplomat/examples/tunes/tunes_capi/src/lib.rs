#[diplomat::bridge]
#[diplomat::abi_rename = "tunes_{0}_mv1"]
mod ffi {
    use tunes::track::{Mixer, Track};
    use tunes::synthesis::waveform::Waveform as TunesWaveform;
    use tunes::composition::timing::tempo::Tempo;

    pub enum Waveform {
        Sine,
        Square,
        Sawtooth,
        Triangle,
    }

    #[diplomat::opaque]
    pub struct TunesError(pub String);

    impl TunesError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            use std::fmt::Write;
            let _ = write!(write, "{}", self.0);
        }
    }

    fn to_tunes_waveform(wf: Waveform) -> TunesWaveform {
        match wf {
            Waveform::Sine     => TunesWaveform::Sine,
            Waveform::Square   => TunesWaveform::Square,
            Waveform::Sawtooth => TunesWaveform::Sawtooth,
            Waveform::Triangle => TunesWaveform::Triangle,
        }
    }

    #[diplomat::opaque_mut]
    pub struct TunesMixer(Mixer);

    impl TunesMixer {
        pub fn new(bpm: f32) -> Box<TunesMixer> {
            Box::new(TunesMixer(Mixer::new(Tempo::new(bpm))))
        }

        pub fn add_note(
            &mut self,
            freq_hz: f32,
            start_time: f32,
            duration: f32,
            waveform: Waveform,
        ) {
            let wf = to_tunes_waveform(waveform);
            let mut track = Track::new();
            track.add_note_with_waveform(&[freq_hz], start_time, duration, wf);
            self.0.add_track(track);
        }

        pub fn add_chord(
            &mut self,
            freqs: &[f32],
            start_time: f32,
            duration: f32,
            waveform: Waveform,
        ) {
            let wf = to_tunes_waveform(waveform);
            let mut track = Track::new();
            track.add_note_with_waveform(freqs, start_time, duration, wf);
            self.0.add_track(track);
        }

        pub fn clear(&mut self) {
            self.0 = Mixer::new(Tempo::new(self.0.tempo.bpm));
        }

        pub fn disable_cache(&mut self) {
            self.0.disable_cache();
        }

        pub fn total_duration(&self) -> f32 {
            self.0.total_duration()
        }

        pub fn render_buffer_size(&mut self, sample_rate: f32) -> usize {
            (self.0.total_duration() * sample_rate).ceil() as usize * 2
        }

        pub fn render_into(&mut self, buf: &mut [f32], sample_rate: f32) {
            let rendered = self.0.render_to_buffer(sample_rate);
            let n = rendered.len().min(buf.len());
            buf[..n].copy_from_slice(&rendered[..n]);
        }

        pub fn process_block(&mut self, buf: &mut [f32], sample_rate: f32, start_time: f32) {
            self.0.process_block(buf, sample_rate, start_time, None, None);
        }

        pub fn export_wav(
            &mut self,
            path: &DiplomatStr,
            sample_rate: u32,
        ) -> Result<(), Box<TunesError>> {
            let path_str = std::str::from_utf8(path)
                .map_err(|e| Box::new(TunesError(e.to_string())))?;
            self.0
                .export_wav(path_str, sample_rate)
                .map_err(|e| Box::new(TunesError(e.to_string())))
        }
    }
}
