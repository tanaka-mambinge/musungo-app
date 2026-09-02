import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  Animated,
  BackHandler,
  Image,
  Modal,
  NativeEventEmitter,
  NativeModules,
  PermissionsAndroid,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import IconAdjustmentsHorizontal from '@tabler/icons-react-native/IconAdjustmentsHorizontal';
import IconAdjustmentsSpark from '@tabler/icons-react-native/IconAdjustmentsSpark';
import IconBattery from '@tabler/icons-react-native/IconBattery';
import IconBattery1 from '@tabler/icons-react-native/IconBattery1';
import IconBattery2 from '@tabler/icons-react-native/IconBattery2';
import IconBattery3 from '@tabler/icons-react-native/IconBattery3';
import IconBattery4 from '@tabler/icons-react-native/IconBattery4';
import IconBatteryCharging from '@tabler/icons-react-native/IconBatteryCharging';
import IconCircleDot from '@tabler/icons-react-native/IconCircleDot';
import IconCircleDotted from '@tabler/icons-react-native/IconCircleDotted';
import IconCircleCheck from '@tabler/icons-react-native/IconCircleCheck';
import IconCircleOff from '@tabler/icons-react-native/IconCircleOff';
import IconChartDots from '@tabler/icons-react-native/IconChartDots';
import IconChevronLeft from '@tabler/icons-react-native/IconChevronLeft';
import IconChevronRight from '@tabler/icons-react-native/IconChevronRight';
import IconDeviceAirpods from '@tabler/icons-react-native/IconDeviceAirpods';
import IconDeviceAirpodsCase from '@tabler/icons-react-native/IconDeviceAirpodsCase';
import IconDeviceGamepad2 from '@tabler/icons-react-native/IconDeviceGamepad2';
import IconHandClick from '@tabler/icons-react-native/IconHandClick';
import IconInfoCircle from '@tabler/icons-react-native/IconInfoCircle';
import IconMicrophone from '@tabler/icons-react-native/IconMicrophone';
import IconPhoneOff from '@tabler/icons-react-native/IconPhoneOff';
import IconPencil from '@tabler/icons-react-native/IconPencil';
import IconPlayerPlay from '@tabler/icons-react-native/IconPlayerPlay';
import IconPlayerTrackNext from '@tabler/icons-react-native/IconPlayerTrackNext';
import IconScan from '@tabler/icons-react-native/IconScan';
import IconVolume from '@tabler/icons-react-native/IconVolume';
import IconVolume3 from '@tabler/icons-react-native/IconVolume3';
import IconX from '@tabler/icons-react-native/IconX';
import {
  SafeAreaProvider,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';

type ConnectionStatus = 'connected' | 'connecting' | 'disconnected' | 'scanning';

interface EarbudState {
  status: ConnectionStatus;
  deviceName: string | null;
  productName: string | null;
  left: number | null;
  right: number | null;
  case: number | null;
  leftCharging: boolean;
  rightCharging: boolean;
  caseCharging: boolean;
  batteryReady: boolean;
  message: string | null;
  shouldScan: boolean;
}

interface JieliNativeModule {
  startScan(): void;
  stopScan(): void;
  setEqMode(mode: number): void;
  probeAudioMode(mode: number): void;
  queryAudioMode(): void;
  setAncMode(mode: number): void;
  setGameMode(enabled: boolean): void;
  setDeviceName(name: string): void;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

const jieli = NativeModules.JieliModule as JieliNativeModule;
const jieliEvents = new NativeEventEmitter(jieli);
const STATE_EVENT = 'jieliStateChanged';
const CONTROLS_EVENT = 'jieliControlsChanged';
const MUSUNGO_LOGO = require('./assets/musungo_logo.png');
const ZENVIBE_DEVICE_IMAGE = require('./assets/musungo_zen_vibe_2.png');
const SHOW_SOUND_CONTROLS = false;
const SHOW_CASE_CARD = false;
export const MAX_DEVICE_NAME_BYTES = 31;

type EqPreset = { mode: number; values: number[]; dynamic: boolean };

interface ControlsState {
  eqPresets: EqPreset[];
  eqMode: number | null;
  eqValues: number[];
  ancModes: number[];
  ancMode: number | null;
  caseStatus: 'open' | 'closed' | null;
  controlsMessage: string | null;
  gameMode: boolean | null;
}

const initialControls: ControlsState = {
  eqPresets: [],
  eqMode: null,
  eqValues: [],
  ancModes: [],
  ancMode: null,
  caseStatus: null,
  controlsMessage: null,
  gameMode: null,
};

type RenamePhase = 'idle' | 'saving' | 'success' | 'error';

const EQ_NAMES: Record<number, string> = {
  0: 'Standard',
  1: 'Rock',
  2: 'Popular',
  3: 'Classical',
  4: 'Jazz',
  5: 'Country',
  6: 'Custom',
};

const ANC_NAMES: Record<number, string> = {
  0: 'Off',
  1: 'Noise cancel',
  2: 'Transparency',
};

const initialState: EarbudState = {
  status: 'scanning',
  deviceName: null,
  productName: null,
  left: null,
  right: null,
  case: null,
  leftCharging: false,
  rightCharging: false,
  caseCharging: false,
  batteryReady: false,
  message: null,
  shouldScan: false,
};

export function utf8ByteLength(value: string): number {
  let length = 0;
  for (const character of value) {
    const codePoint = character.codePointAt(0) ?? 0;
    length += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
  }
  return length;
}

export function buildDeviceName(ownerName: string, productName: string): string {
  const normalizedOwnerName = ownerName.trim().replace(/ +/g, ' ');
  return normalizedOwnerName ? `${normalizedOwnerName}'s ${productName}` : '';
}

export function validateOwnerName(ownerName: string, productName: string): string | null {
  if (!ownerName) {
    return 'Enter your name.';
  }
  if (!/^[a-zA-Z0-9]+$/.test(ownerName)) {
    return 'Use letters and numbers only.';
  }
  const finalName = buildDeviceName(ownerName, productName);
  if (utf8ByteLength(finalName) > MAX_DEVICE_NAME_BYTES) {
    return 'That name is too long for the earbuds.';
  }
  return null;
}

function ownerNameFromDeviceName(deviceName: string | null, productName: string | null): string {
  if (!deviceName || !productName) {
    return '';
  }
  const suffix = `'s ${productName}`;
  return deviceName.endsWith(suffix) ? deviceName.slice(0, -suffix.length) : '';
}

async function requestBluetoothAndScan() {
  if (Platform.OS !== 'android') {
    return;
  }

  const permissions = [
    PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
    PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
    ...(Platform.Version < 31 ? [PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION] : []),
  ];

  const result = await PermissionsAndroid.requestMultiple(permissions);
  const permitted = permissions.every(
    permission => result[permission] === PermissionsAndroid.RESULTS.GRANTED,
  );

  if (permitted) {
    jieli.startScan();
  }
}

function stabilizeBattery(previous: number | null, next: number | null, charging: boolean) {
  if (charging && previous !== null && next !== null && next < previous) {
    return previous;
  }
  return next;
}

function ListeningModeOption({
  mode,
  label,
  selected,
  onPress,
}: {
  mode: number;
  label: string;
  selected: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable onPress={onPress} style={styles.modeOption}>
      <View style={[styles.modeCircle, selected && styles.selectedModeCircle]}>
        {mode === 0 ? (
          <IconCircleOff size={26} color={selected ? '#1b1b1b' : '#bdbdbd'} strokeWidth={1.7} />
        ) : mode === 1 ? (
          <IconCircleDot size={26} color={selected ? '#1b1b1b' : '#bdbdbd'} strokeWidth={1.7} />
        ) : (
          <IconCircleDotted size={26} color={selected ? '#1b1b1b' : '#bdbdbd'} strokeWidth={1.7} />
        )}
      </View>
      <Text style={[styles.modeOptionText, selected && styles.selectedModeOptionText]}>{label}</Text>
    </Pressable>
  );
}

function BatteryMetric({
  label,
  value,
  charging,
}: {
  label: string;
  value: number | null;
  charging: boolean;
}) {
  const BatteryIcon = charging
    ? IconBatteryCharging
    : value === null || value < 20
      ? IconBattery
      : value < 40
        ? IconBattery1
        : value < 60
          ? IconBattery2
          : value < 80
            ? IconBattery3
            : IconBattery4;

  return (
    <View style={styles.batteryMetric}>
      <View style={styles.metricTrack}>
        <View style={[styles.metricFill, { width: `${value ?? 0}%` }]} />
      </View>
      <View style={styles.metricLabelRow}>
        <Text style={styles.metricLabel}>{label}</Text>
        <BatteryIcon size={15} color={charging ? '#70df90' : '#bcbcbc'} strokeWidth={1.8} />
        <Text style={styles.metricValue}>{value === null ? '—' : `${value}%`}</Text>
      </View>
    </View>
  );
}

function GamingModeToggle({
  value,
  disabled,
  onChange,
}: {
  value: boolean;
  disabled: boolean;
  onChange: (value: boolean) => void;
}) {
  const progress = useRef(new Animated.Value(value ? 1 : 0)).current;

  useEffect(() => {
    Animated.spring(progress, {
      friction: 8,
      tension: 80,
      toValue: value ? 1 : 0,
      useNativeDriver: true,
    }).start();
  }, [progress, value]);

  return (
    <Pressable
      accessibilityLabel="Gaming mode"
      accessibilityRole="switch"
      accessibilityState={{checked: value, disabled}}
      disabled={disabled}
      onPress={() => onChange(!value)}
      style={[styles.gameToggle, value && styles.gameToggleActive, disabled && styles.gameToggleDisabled]}
    >
      <Animated.View
        style={[
          styles.gameToggleThumb,
          {transform: [{translateX: progress.interpolate({inputRange: [0, 1], outputRange: [0, 24]})}]},
        ]}
      />
    </Pressable>
  );
}

function SkeletonBlock({style}: {style?: StyleProp<ViewStyle>}) {
  return <View style={[styles.skeletonBlock, style]} />;
}

function LoadingSkeletonPage() {
  const pulse = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    const animation = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, {toValue: 1, duration: 850, useNativeDriver: true}),
        Animated.timing(pulse, {toValue: 0, duration: 850, useNativeDriver: true}),
      ]),
    );
    animation.start();
    return () => animation.stop();
  }, [pulse]);

  return (
    <Animated.View
      style={[
        styles.content,
        {opacity: pulse.interpolate({inputRange: [0, 1], outputRange: [0.62, 1]})},
      ]}
    >
      <ScrollView contentContainerStyle={styles.contentContainer} showsVerticalScrollIndicator={false}>
        <View style={styles.heroCard}>
          <SkeletonBlock style={styles.skeletonDeviceVisual} />
          <SkeletonBlock style={styles.skeletonDeviceName} />
          <SkeletonBlock style={styles.skeletonConnectionChip} />
          <View style={styles.heroBatteryRow}>
            <View style={styles.batteryMetric}>
              <SkeletonBlock style={styles.skeletonMetricTrack} />
              <SkeletonBlock style={styles.skeletonMetricLabel} />
            </View>
            <View style={styles.batteryMetric}>
              <SkeletonBlock style={styles.skeletonMetricTrack} />
              <SkeletonBlock style={styles.skeletonMetricLabel} />
            </View>
          </View>
        </View>

        <View style={styles.modesCard}>
          <View style={styles.sectionHeadingRow}>
            <SkeletonBlock style={styles.skeletonSectionHeading} />
            <SkeletonBlock style={styles.skeletonInfoButton} />
          </View>
          <View style={styles.modeRail}>
            <SkeletonBlock style={styles.skeletonModeCircle} />
            <SkeletonBlock style={styles.skeletonModeCircle} />
            <SkeletonBlock style={styles.skeletonModeCircle} />
          </View>
          <View style={styles.skeletonModeLabels}>
            <SkeletonBlock style={styles.skeletonModeLabel} />
            <SkeletonBlock style={styles.skeletonModeLabel} />
            <SkeletonBlock style={styles.skeletonModeLabel} />
          </View>
        </View>

        <View style={styles.featureCard}>
          <SkeletonBlock style={styles.skeletonFeatureIcon} />
          <View style={styles.skeletonFeatureCopy}>
            <SkeletonBlock style={styles.skeletonFeatureTitle} />
            <SkeletonBlock style={styles.skeletonFeatureDescription} />
          </View>
          <SkeletonBlock style={styles.skeletonSwitch} />
        </View>

        <View style={styles.guideCard}>
          <View style={styles.guideTitleWrap}>
            <SkeletonBlock style={styles.skeletonGuideIcon} />
            <View>
              <SkeletonBlock style={styles.skeletonGuideTitle} />
              <SkeletonBlock style={styles.skeletonGuideCopy} />
            </View>
          </View>
          <SkeletonBlock style={styles.skeletonChevron} />
        </View>

        {SHOW_SOUND_CONTROLS ? <View style={[styles.controlsSection, styles.eqCard]}>
          <View style={styles.sectionHeadingRow}>
            <SkeletonBlock style={styles.skeletonSectionHeading} />
            <SkeletonBlock style={styles.skeletonInfoButton} />
          </View>
          <View style={styles.skeletonChipRow}>
            <SkeletonBlock style={styles.skeletonSoundChip} />
            <SkeletonBlock style={styles.skeletonSoundChip} />
            <SkeletonBlock style={styles.skeletonSoundChip} />
          </View>
        </View> : null}
      </ScrollView>
    </Animated.View>
  );
}

function ScanningMark() {
  const pulse = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    const animation = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue: 1, duration: 900, useNativeDriver: true }),
        Animated.timing(pulse, { toValue: 0, duration: 900, useNativeDriver: true }),
      ]),
    );
    animation.start();
    return () => animation.stop();
  }, [pulse]);

  return (
    <View style={styles.scanMarkWrap}>
      <Animated.View
        style={[
          styles.scanHalo,
          {
            opacity: pulse.interpolate({ inputRange: [0, 1], outputRange: [0.2, 0.65] }),
            transform: [
              { scale: pulse.interpolate({ inputRange: [0, 1], outputRange: [0.8, 1.18] }) },
            ],
          },
        ]}
      />
      <View style={styles.scanMark}>
        <IconScan size={44} color="#d8c3ff" strokeWidth={1.5} />
      </View>
    </View>
  );
}

function ModalHeader({title, onClose}: {title: string; onClose: () => void}) {
  return (
    <View style={styles.modalHeader}>
      <Text style={styles.modalTitle}>{title}</Text>
      <Pressable accessibilityLabel="Close" onPress={onClose} style={styles.modalClose}>
        <IconX size={20} color="#d8d8d8" strokeWidth={1.8} />
      </Pressable>
    </View>
  );
}

function NoiseControlsModal({visible, onClose}: {visible: boolean; onClose: () => void}) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <Pressable style={styles.modalBackdrop} onPress={onClose}>
        <Pressable style={styles.modalCard} onPress={() => undefined}>
          <View style={styles.modalAccent} />
          <ModalHeader title="Noise controls" onClose={onClose} />
          <View style={styles.modalOption}>
            <IconCircleOff size={24} color="#d8d8d8" strokeWidth={1.7} />
            <View style={styles.modalOptionCopy}>
              <Text style={styles.modalOptionTitle}>Off</Text>
              <Text style={styles.modalOptionText}>Turns off the earbuds’ active noise processing.</Text>
            </View>
          </View>
          <View style={styles.modalOption}>
            <IconCircleDot size={24} color="#70df90" strokeWidth={1.7} />
            <View style={styles.modalOptionCopy}>
              <Text style={styles.modalOptionTitle}>Noise cancel</Text>
              <Text style={styles.modalOptionText}>Reduces steady outside noise using the earbuds’ microphones.</Text>
            </View>
          </View>
          <View style={styles.modalOption}>
            <IconCircleDotted size={24} color="#d8d8d8" strokeWidth={1.7} />
            <View style={styles.modalOptionCopy}>
              <Text style={styles.modalOptionTitle}>Transparency</Text>
              <Text style={styles.modalOptionText}>Feeds surrounding sound through so you can stay aware.</Text>
            </View>
          </View>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

function SoundControlsModal({visible, onClose}: {visible: boolean; onClose: () => void}) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <Pressable style={styles.modalBackdrop} onPress={onClose}>
        <Pressable style={styles.modalCard} onPress={() => undefined}>
          <View style={[styles.modalAccent, styles.soundModalAccent]} />
          <ModalHeader title="Sound controls" onClose={onClose} />
          <Text style={styles.modalIntro}>
            These are the EQ profiles reported by the earbuds’ firmware. The app reads the active mode back after every device update, including changes made with the touch controls.
          </Text>
          <View style={styles.modalCallout}>
            <IconAdjustmentsSpark size={20} color="#d8c3ff" strokeWidth={1.7} />
            <Text style={styles.modalCalloutText}>
              This device currently exposes Jieli’s generic firmware modes, not verified “Balanced” and “Signature” names. Profile switching is read-only here while we prevent the firmware’s low-volume EQ behavior from being triggered.
            </Text>
          </View>
          <Text style={styles.modalFootnote}>
            A vendor profile map or firmware-specific protocol is needed to safely restore writes and confirm whether a profile is stored on the earbuds.
          </Text>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

function RenameModal({
  visible,
  connected,
  ownerName,
  productName,
  phase,
  message,
  onOwnerNameChange,
  onClose,
  onSave,
}: {
  visible: boolean;
  connected: boolean;
  ownerName: string;
  productName: string | null;
  phase: RenamePhase;
  message: string | null;
  onOwnerNameChange: (value: string) => void;
  onClose: () => void;
  onSave: () => void;
}) {
  if (!productName) {
    return null;
  }

  const finalName = buildDeviceName(ownerName, productName);
  const validationMessage = validateOwnerName(ownerName, productName);
  const isComplete = phase === 'success';
  const isSaving = phase === 'saving';
  const isNameTooLong = validationMessage === 'That name is too long for the earbuds.';
  const feedbackMessage = isNameTooLong ? 'Name too long' : message;

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <Pressable style={styles.modalBackdrop} onPress={onClose}>
        <Pressable style={styles.modalCard} onPress={() => undefined}>
          <View style={styles.modalAccent} />
          <ModalHeader title="Name your earbuds" onClose={onClose} />
          <Text style={styles.renameLabel}>Your name</Text>
          <TextInput
            autoCapitalize="words"
            autoCorrect={false}
            editable={!isSaving && !isComplete}
            maxLength={64}
            onChangeText={value => onOwnerNameChange(value.replace(/[^a-zA-Z0-9]/g, ''))}
            placeholder="e.g. Tawanda"
            placeholderTextColor="#777777"
            style={styles.renameInput}
            value={ownerName}
          />
          <Text style={styles.renamePreview}>{finalName || `Your name's ${productName}`}</Text>
          <Text style={[styles.renameCounter, isNameTooLong && styles.renameCounterError]}>
            {utf8ByteLength(finalName)} / {MAX_DEVICE_NAME_BYTES}
          </Text>
          {feedbackMessage ? (
            <Text style={[styles.renameFeedback, phase === 'success' && styles.renameSuccess]}>{feedbackMessage}</Text>
          ) : null}
          {!connected && !isComplete && !isSaving ? <Text style={styles.renameFeedback}>Connect your earbuds before saving.</Text> : null}
          <Pressable
            accessibilityRole="button"
            disabled={(!isComplete && (!!validationMessage || !connected || isSaving))}
            onPress={isComplete ? onClose : onSave}
            style={[styles.primaryButton, styles.renameAction, (!isComplete && (!!validationMessage || !connected || isSaving)) && styles.disabledButton]}
          >
            <Text style={styles.primaryButtonText}>{isComplete ? 'DONE' : isSaving ? 'SAVING…' : 'SAVE'}</Text>
          </Pressable>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

function GestureInstruction({icon, gesture, side, copy}: {icon: ReactNode; gesture: string; side: string; copy: string}) {
  return (
    <View style={styles.gestureInstruction}>
      <View style={styles.gestureIcon}>{icon}</View>
      <View style={styles.gestureInstructionCopy}>
        <View style={styles.gestureMetaRow}>
          <Text style={styles.gestureAction}>{gesture}</Text>
          <Text style={styles.gestureSide}>{side}</Text>
        </View>
        <Text style={styles.gestureDescription}>{copy}</Text>
      </View>
    </View>
  );
}

function GestureGuidePage() {
  return (
    <ScrollView style={styles.content} contentContainerStyle={styles.gesturePageContainer} showsVerticalScrollIndicator={false}>
      <View style={styles.pageIntro}>
        <Text style={styles.pageEyebrow}>ZEN VIBE 2</Text>
        <Text style={styles.pageTitle}>Touch controls</Text>
      </View>

      <Text style={styles.guideLead}>A quick reference for the touch gestures on your earbuds.</Text>

      <View style={styles.guideNotice}>
        <IconHandClick size={20} color="#d8c3ff" strokeWidth={1.7} />
        <Text style={styles.guideNoticeText}>Use a light, deliberate touch on the outer surface while the bud is in your ear.</Text>
      </View>

      <View style={styles.gestureSectionCard}>
        <Text style={styles.gestureSectionTitle}>Everyday controls</Text>
        <GestureInstruction
          icon={<IconPlayerPlay size={21} color="#d8c3ff" strokeWidth={1.7} />}
          gesture="Single tap"
          side="Left or right"
          copy="Play or pause music. During a call, answer the call."
        />
        <GestureInstruction
          icon={<IconPlayerTrackNext size={21} color="#d8c3ff" strokeWidth={1.7} />}
          gesture="Double tap"
          side="Left or right"
          copy="Move between tracks. The manual’s illustrations show the left/right assignment."
        />
        <GestureInstruction
          icon={<IconPhoneOff size={21} color="#d8c3ff" strokeWidth={1.7} />}
          gesture="Double tap"
          side="During a call"
          copy="Hang up or reject the call."
        />
      </View>

      <View style={styles.gestureSectionCard}>
        <Text style={styles.gestureSectionTitle}>Device controls</Text>
        <GestureInstruction
          icon={<IconVolume3 size={21} color="#70df90" strokeWidth={1.7} />}
          gesture="Hold · 3 sec"
          side="Left"
          copy="Cycle between the Balanced and Signature sound profiles."
        />
        <GestureInstruction
          icon={<IconVolume size={21} color="#70df90" strokeWidth={1.7} />}
          gesture="Hold · 3 sec"
          side="Right"
          copy="Switch ANC modes."
        />
        <GestureInstruction
          icon={<IconDeviceGamepad2 size={21} color="#70df90" strokeWidth={1.7} />}
          gesture="Triple tap"
          side="Left"
          copy="Turn game mode on or off."
        />
        <GestureInstruction
          icon={<IconMicrophone size={21} color="#70df90" strokeWidth={1.7} />}
          gesture="Triple tap"
          side="Right"
          copy="Start voice dialling with Google Assistant or Siri."
        />
      </View>

    </ScrollView>
  );
}

function AppContent() {
  const insets = useSafeAreaInsets();
  const [earbuds, setEarbuds] = useState<EarbudState>(initialState);
  const [controls, setControls] = useState<ControlsState>(initialControls);
  const [noiseInfoVisible, setNoiseInfoVisible] = useState(false);
  const [soundInfoVisible, setSoundInfoVisible] = useState(false);
  const [activePage, setActivePage] = useState<'home' | 'gestures'>('home');
  const [renameVisible, setRenameVisible] = useState(false);
  const [renameOwnerName, setRenameOwnerName] = useState('');
  const [renamePhase, setRenamePhase] = useState<RenamePhase>('idle');
  const [renameMessage, setRenameMessage] = useState<string | null>(null);
  const mounted = useRef(true);
  const scanInFlight = useRef(false);

  const scan = useCallback(async () => {
    if (scanInFlight.current) {
      return;
    }
    scanInFlight.current = true;
    try {
      await requestBluetoothAndScan();
    } finally {
      scanInFlight.current = false;
    }
  }, []);

  useEffect(() => {
    const subscription = jieliEvents.addListener(STATE_EVENT, next => {
      if (!mounted.current) {
        return;
      }
      const state = next as EarbudState;
      setEarbuds(current => {
        const sameConnectedSession = current.status === 'connected' && state.status === 'connected';
        return {
          ...current,
          ...state,
          left: sameConnectedSession && state.left === null ? current.left : stabilizeBattery(current.left, state.left, state.leftCharging),
          right: sameConnectedSession && state.right === null ? current.right : stabilizeBattery(current.right, state.right, state.rightCharging),
          case: sameConnectedSession && state.case === null ? current.case : state.case,
          leftCharging: sameConnectedSession && state.left === null ? current.leftCharging : state.leftCharging,
          rightCharging: sameConnectedSession && state.right === null ? current.rightCharging : state.rightCharging,
          caseCharging: sameConnectedSession && state.case === null ? current.caseCharging : state.caseCharging,
        };
      });
      if (state.shouldScan && state.status !== 'scanning' && state.status !== 'connecting') {
        void scan();
      }
    });
    const controlsSubscription = jieliEvents.addListener(CONTROLS_EVENT, next => {
      const event = next as Partial<ControlsState> & {renameStatus?: RenamePhase; renameMessage?: string | null};
      setControls(current => ({ ...current, ...(event as Partial<ControlsState>) }));
      if (event.renameStatus) {
        setRenamePhase(event.renameStatus);
        setRenameMessage(event.renameMessage ?? null);
      }
    });

    void scan();

    return () => {
      mounted.current = false;
      subscription.remove();
      controlsSubscription.remove();
      jieli.stopScan();
    };
  }, [scan]);

  useEffect(() => {
    if (activePage !== 'gestures') {
      return;
    }
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      setActivePage('home');
      return true;
    });
    return () => subscription.remove();
  }, [activePage]);

  const connected = earbuds.status === 'connected';
  const scanning = earbuds.status === 'scanning' || earbuds.status === 'connecting';
  const loadingDeviceData = connected && (
    !earbuds.batteryReady ||
    controls.ancModes.length === 0 ||
    controls.ancMode === null ||
    controls.gameMode === null
  );
  const ready = connected && !loadingDeviceData;
  const caseAvailable = earbuds.case !== null || controls.caseStatus !== null || earbuds.caseCharging;
  const caseStatusLabel = controls.caseStatus === null ? 'Unavailable' : controls.caseStatus === 'open' ? 'Open' : 'Closed';
  const casePowerLabel = earbuds.caseCharging ? 'Charging' : earbuds.case === null ? 'Unavailable' : 'Not charging';

  const openRename = () => {
    if (!connected || !earbuds.productName) {
      return;
    }
    setRenameOwnerName(ownerNameFromDeviceName(earbuds.deviceName, earbuds.productName));
    setRenamePhase('idle');
    setRenameMessage(null);
    setRenameVisible(true);
  };

  const closeRename = () => {
    if (renamePhase === 'saving') {
      return;
    }
    setRenameVisible(false);
  };

  const saveRename = () => {
    if (!earbuds.productName || !connected) {
      return;
    }
    const validationMessage = validateOwnerName(renameOwnerName, earbuds.productName);
    if (validationMessage) {
      setRenamePhase('error');
      setRenameMessage(validationMessage === 'That name is too long for the earbuds.' ? 'Name too long' : null);
      return;
    }
    setRenamePhase('saving');
    setRenameMessage(null);
    jieli.setDeviceName(buildDeviceName(renameOwnerName, earbuds.productName));
  };

  return (
    <View style={[styles.screen, { paddingTop: insets.top + 18 }]}> 
      <StatusBar barStyle="light-content" />
      <View style={styles.header}>
        {activePage === 'gestures' ? (
          <Pressable accessibilityLabel="Back to earbuds" onPress={() => setActivePage('home')} style={styles.headerBackButton}>
            <IconChevronLeft size={21} color="#f4f7fb" strokeWidth={1.8} />
          </Pressable>
        ) : null}
        <Image source={MUSUNGO_LOGO} style={styles.brandLogo} resizeMode="contain" />
      </View>

      {ready ? activePage === 'gestures' ? (
        <GestureGuidePage />
      ) : (
        <ScrollView style={styles.content} contentContainerStyle={styles.contentContainer} showsVerticalScrollIndicator={false}>
          <View style={styles.heroCard}>
            <View style={styles.heroDeviceVisual}>
              {earbuds.productName?.toLowerCase().includes('zenvibe') ? (
                <Image source={ZENVIBE_DEVICE_IMAGE} style={styles.heroDeviceImage} resizeMode="contain" />
              ) : (
                <View style={styles.heroBud}>
                  <IconDeviceAirpods size={58} color="#f2f2f2" strokeWidth={1.35} />
                </View>
              )}
            </View>
            <View style={styles.deviceNameRow}>
              <Text style={styles.deviceName}>{earbuds.deviceName ?? earbuds.productName ?? 'Earbuds'}</Text>
              <Pressable
                accessibilityLabel="Rename earbuds"
                accessibilityRole="button"
                disabled={!earbuds.productName}
                onPress={openRename}
                style={[styles.renameButton, !earbuds.productName && styles.disabledButton]}
              >
                <IconPencil size={18} color="#d8c3ff" strokeWidth={1.8} />
              </Pressable>
            </View>
            <View style={styles.connectionChip}>
              <IconCircleCheck size={14} color="#70df90" strokeWidth={1.9} />
              <Text style={styles.connectionChipText}>Connected</Text>
            </View>
            <View style={styles.heroBatteryRow}>
              <BatteryMetric label="L" value={earbuds.left} charging={earbuds.leftCharging} />
              <BatteryMetric label="R" value={earbuds.right} charging={earbuds.rightCharging} />
            </View>
          </View>

          <View style={styles.modesCard}>
            <View style={styles.sectionHeadingRow}>
              <View style={styles.sectionTitleWrap}>
                <IconVolume size={20} color="#f4f7fb" strokeWidth={1.8} />
                <Text style={styles.sectionHeading}>Noise controls</Text>
              </View>
              <Pressable accessibilityLabel="Explain noise controls" onPress={() => setNoiseInfoVisible(true)} style={styles.infoButton}>
                <IconInfoCircle size={20} color="#aeb4bd" strokeWidth={1.7} />
              </Pressable>
            </View>
            <View style={styles.modeRail}>
              {controls.ancModes.length > 0 ? controls.ancModes.map(mode => (
                <ListeningModeOption
                  key={mode}
                  mode={mode}
                  label={ANC_NAMES[mode] ?? `Mode ${mode}`}
                  selected={controls.ancMode === mode}
                  onPress={() => jieli.setAncMode(mode)}
                />
              )) : <Text style={styles.controlEmpty}>Reading listening modes…</Text>}
            </View>
          </View>

          <View style={styles.featureCard}>
            <View style={styles.featureTitleWrap}>
              <View style={styles.featureIcon}>
                <IconDeviceGamepad2 size={21} color="#d8c3ff" strokeWidth={1.7} />
              </View>
              <View style={styles.featureCopy}>
                <Text style={styles.featureHeading}>Gaming mode</Text>
                <Text style={styles.featureDescription}>Lower audio delay while you play</Text>
              </View>
            </View>
            <GamingModeToggle
              disabled={controls.gameMode === null}
              onChange={value => jieli.setGameMode(value)}
              value={controls.gameMode === true}
            />
          </View>

          {SHOW_CASE_CARD && caseAvailable ? <View style={styles.caseCompactCard}>
            <View style={styles.caseCompactTitle}>
              <IconDeviceAirpodsCase size={21} color="#ffc66d" strokeWidth={1.7} />
              <View>
                <Text style={styles.caseCompactHeading}>Charging case</Text>
                <Text style={styles.caseCompactStatus}>
                  {caseStatusLabel}
                  {' · '}
                  {casePowerLabel}
                </Text>
              </View>
            </View>
            <Text style={styles.caseCompactValue}>{earbuds.case === null ? '—' : `${earbuds.case}%`}</Text>
          </View> : null}

          <Pressable style={styles.guideCard} onPress={() => setActivePage('gestures')}>
            <View style={styles.guideTitleWrap}>
              <IconHandClick size={22} color="#d8c3ff" strokeWidth={1.7} />
              <View>
                <Text style={styles.guideHeading}>Touch controls</Text>
                <Text style={styles.guideCopy}>See the gestures for your buds</Text>
              </View>
            </View>
            <IconChevronRight size={20} color="#aeb4bd" strokeWidth={1.8} />
          </Pressable>

          {earbuds.message ? <Text style={styles.infoMessage}>{earbuds.message}</Text> : null}

        {SHOW_SOUND_CONTROLS ? <View style={[styles.controlsSection, styles.eqCard]}>
            <View style={styles.sectionHeadingRow}>
              <View style={styles.sectionTitleWrap}>
                <IconAdjustmentsHorizontal size={20} color="#f4f7fb" strokeWidth={1.8} />
                <Text style={styles.sectionHeading}>Sound controls</Text>
              </View>
              <Pressable accessibilityLabel="Explain sound controls" onPress={() => setSoundInfoVisible(true)} style={styles.infoButton}>
                <IconInfoCircle size={20} color="#aeb4bd" strokeWidth={1.7} />
              </Pressable>
            </View>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chipRow}>
              {controls.eqPresets.filter(preset => preset.mode !== 6).length > 0 ? controls.eqPresets.filter(preset => preset.mode !== 6).map(preset => (
                <View
                  key={preset.mode}
                  style={[styles.soundProfileChip, controls.eqMode === preset.mode && styles.selectedSoundProfileChip]}
                >
                  {preset.mode === 0 ? (
                    <IconAdjustmentsSpark size={18} color={controls.eqMode === preset.mode ? '#191919' : '#d8c3ff'} strokeWidth={1.7} />
                  ) : (
                    <IconChartDots size={18} color={controls.eqMode === preset.mode ? '#191919' : '#d8c3ff'} strokeWidth={1.7} />
                  )}
                  <Text style={[styles.soundProfileText, controls.eqMode === preset.mode && styles.selectedSoundProfileText]}>
                    {EQ_NAMES[preset.mode] ?? `Mode ${preset.mode}`}
                  </Text>
                </View>
              )) : <Text style={styles.controlEmpty}>Reading EQ presets…</Text>}
            </ScrollView>

            {controls.controlsMessage ? <Text style={styles.infoMessage}>{controls.controlsMessage}</Text> : null}
          </View> : null}
        </ScrollView>
      ) : connected ? (
        <LoadingSkeletonPage />
      ) : (
        <View style={styles.disconnectedContent}>
          <ScanningMark />
          <Text style={styles.disconnectedHeading}>
            {earbuds.status === 'connecting'
              ? 'Connecting to your earbuds'
              : earbuds.status === 'scanning'
                ? 'Looking for your earbuds'
                : 'Your earbuds are away'}
          </Text>
          <Text style={styles.disconnectedCopy}>
            {earbuds.status === 'connecting'
              ? 'Pairing with the earbuds now. This should only take a moment.'
              : earbuds.status === 'scanning'
                ? 'Keep the case open and nearby. We’ll connect automatically.'
                : earbuds.message ?? 'Open the case and scan when you’re ready to reconnect.'}
          </Text>
          <Pressable disabled={scanning} style={[styles.primaryButton, scanning && styles.disabledButton]} onPress={() => void scan()}>
            <Text style={styles.primaryButtonText}>
              {earbuds.status === 'connecting' ? 'Connecting…' : earbuds.status === 'scanning' ? 'Scanning…' : 'Scan for earbuds'}
            </Text>
          </Pressable>
        </View>
      )}

      <NoiseControlsModal visible={noiseInfoVisible} onClose={() => setNoiseInfoVisible(false)} />
      <SoundControlsModal visible={soundInfoVisible} onClose={() => setSoundInfoVisible(false)} />
      <RenameModal
        connected={connected}
        message={renameMessage}
        onClose={closeRename}
        onOwnerNameChange={value => {
          setRenameOwnerName(value);
          if (renamePhase !== 'saving') {
            setRenamePhase('idle');
            setRenameMessage(null);
          }
        }}
        onSave={saveRename}
        ownerName={renameOwnerName}
        phase={renamePhase}
        productName={earbuds.productName}
        visible={renameVisible}
      />
    </View>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <AppContent />
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#090909',
    paddingHorizontal: 22,
    paddingBottom: 22,
  },
  header: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingBottom: 24,
    position: 'relative',
  },
  brandLogo: { height: 30, width: 152 },
  statusPill: {
    alignItems: 'center',
    borderRadius: 18,
    flexDirection: 'row',
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  connectedPill: { backgroundColor: '#1a3425' },
  searchingPill: { backgroundColor: '#252525' },
  statusDot: { borderRadius: 4, height: 8, marginRight: 7, width: 8 },
  connectedDot: { backgroundColor: '#5ee1a2' },
  searchingDot: { backgroundColor: '#bd9cff' },
  statusText: { color: '#f2f2f2', fontSize: 12, fontWeight: '700' },
  content: { flex: 1, paddingTop: 34 },
  contentContainer: { paddingBottom: 80 },
  gesturePageContainer: { paddingBottom: 110 },
  skeletonBlock: { backgroundColor: '#303030', borderRadius: 8 },
  skeletonDeviceVisual: { borderRadius: 28, height: 150, width: 170 },
  skeletonDeviceName: { borderRadius: 6, height: 22, marginTop: 8, width: 170 },
  skeletonConnectionChip: { borderRadius: 10, height: 24, marginTop: 10, width: 78 },
  skeletonMetricTrack: { height: 4, width: '100%' },
  skeletonMetricLabel: { alignSelf: 'center', height: 14, marginTop: 7, width: 54 },
  skeletonSectionHeading: { height: 19, width: 132 },
  skeletonInfoButton: { borderRadius: 10, height: 20, width: 20 },
  skeletonModeCircle: { borderRadius: 25, height: 50, width: 50 },
  skeletonModeLabels: { flexDirection: 'row', justifyContent: 'space-around', marginTop: 10 },
  skeletonModeLabel: { height: 11, width: 58 },
  skeletonFeatureIcon: { borderRadius: 14, height: 42, width: 42 },
  skeletonFeatureCopy: { flex: 1, marginLeft: 12 },
  skeletonFeatureTitle: { height: 15, width: 105 },
  skeletonFeatureDescription: { height: 11, marginTop: 7, width: 155 },
  skeletonSwitch: { borderRadius: 18, height: 28, width: 50 },
  skeletonGuideIcon: { borderRadius: 14, height: 42, width: 42 },
  skeletonGuideTitle: { height: 15, width: 100 },
  skeletonGuideCopy: { height: 11, marginTop: 7, width: 150 },
  skeletonChevron: { borderRadius: 8, height: 20, width: 20 },
  skeletonChipRow: { flexDirection: 'row', gap: 8, paddingTop: 18 },
  skeletonSoundChip: { borderRadius: 12, height: 42, width: 100 },
  heroCard: {
    alignItems: 'center',
    backgroundColor: '#1b1b1b',
    borderRadius: 28,
    marginBottom: 14,
    padding: 20,
  },
  heroDeviceVisual: {
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 6,
  },
  heroDeviceImage: { height: 150, width: 170 },
  heroBud: {
    alignItems: 'center',
    backgroundColor: '#292929',
    borderRadius: 34,
    height: 68,
    justifyContent: 'center',
    width: 68,
  },
  deviceNameRow: { alignItems: 'center', flexDirection: 'row', gap: 10, justifyContent: 'center', width: '100%' },
  renameButton: { alignItems: 'center', justifyContent: 'center', padding: 6 },
  connectionChip: {
    alignItems: 'center',
    backgroundColor: 'rgba(112, 223, 144, 0.12)',
    borderRadius: 12,
    flexDirection: 'row',
    marginTop: 8,
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  connectionChipText: { color: '#a8dcb6', fontFamily: 'Carlito', fontSize: 11, fontWeight: '600', marginLeft: 6 },
  heroBatteryRow: { flexDirection: 'row', gap: 38, marginTop: 22 },
  batteryMetric: { minWidth: 90 },
  metricTrack: { backgroundColor: '#4a4a4a', borderRadius: 3, height: 4, overflow: 'hidden' },
  metricFill: { backgroundColor: '#70df90', borderRadius: 3, height: 4 },
  metricLabelRow: { alignItems: 'center', flexDirection: 'row', justifyContent: 'center', marginTop: 7 },
  metricLabel: { color: '#eeeeee', fontFamily: 'Carlito', fontSize: 14, fontWeight: '700', marginRight: 5 },
  metricValue: { color: '#eeeeee', fontFamily: 'Carlito', fontSize: 14, fontWeight: '700', marginLeft: 4 },
  deviceRow: {
    alignItems: 'flex-end',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 18,
  },
  deviceHeading: { alignItems: 'center', flexDirection: 'row', gap: 10 },
  deviceName: { color: '#f4f7fb', fontFamily: 'Carlito', fontSize: 22, fontWeight: '700', textAlign: 'center' },
  liveLabel: { color: '#5ee1a2', fontSize: 11, fontWeight: '800', letterSpacing: 1.4 },
  batteryGrid: { flexDirection: 'row', marginBottom: 12 },
  batteryCard: {
    backgroundColor: '#101d2d',
    borderColor: '#1c2d42',
    borderRadius: 18,
    borderWidth: 1,
    marginRight: 6,
    padding: 16,
  },
  gridCard: { flex: 1 },
  cardHeading: { flexDirection: 'row', justifyContent: 'space-between' },
  cardTitleWrap: { alignItems: 'center', flexDirection: 'row', gap: 7 },
  cardTitle: { color: '#9eacbd', fontSize: 12, fontWeight: '700' },
  chargingState: { alignItems: 'center', flexDirection: 'row' },
  chargingLabel: { color: '#5ee1a2', fontSize: 8, fontWeight: '800', letterSpacing: 0.7, marginLeft: 3 },
  batteryValue: { color: '#f4f7fb', fontSize: 29, fontWeight: '700', marginTop: 15 },
  progressTrack: { borderRadius: 3, height: 5, marginTop: 18, overflow: 'hidden' },
  progressFill: { borderRadius: 3, height: 5 },
  cardFootnote: { color: '#718196', fontSize: 11, marginTop: 10 },
  infoMessage: { color: '#ffc66d', fontFamily: 'Carlito', fontSize: 12, lineHeight: 18, marginTop: 16 },
  caseStatusRow: {
    alignItems: 'center',
    flexDirection: 'row',
    marginTop: 12,
    paddingHorizontal: 4,
  },
  caseStatusLabel: { color: '#718196', fontSize: 11 },
  caseStatusValue: { color: '#d5deea', fontSize: 11, fontWeight: '700', marginLeft: 6 },
  caseStatusDivider: { backgroundColor: '#263950', height: 14, marginHorizontal: 12, width: 1 },
  controlsSection: { marginTop: 28 },
  modesCard: { backgroundColor: '#1b1b1b', borderRadius: 28, marginBottom: 14, padding: 20 },
  modeRail: { alignItems: 'flex-start', flexDirection: 'row', justifyContent: 'space-around', marginTop: 24 },
  modeOption: { alignItems: 'center', flex: 1 },
  modeCircle: {
    alignItems: 'center',
    backgroundColor: '#0c0c0c',
    borderColor: '#676767',
    borderRadius: 25,
    borderWidth: 1,
    height: 50,
    justifyContent: 'center',
    width: 50,
  },
  selectedModeCircle: { backgroundColor: '#f4f4f4', borderColor: '#f4f4f4' },
  modeCircleDot: { backgroundColor: '#7b7b7b', borderRadius: 8, height: 16, width: 16 },
  selectedModeCircleDot: { backgroundColor: '#1b1b1b' },
  modeOptionText: { color: '#c0c0c0', fontFamily: 'Carlito', fontSize: 11, fontWeight: '600', marginTop: 10, textAlign: 'center' },
  selectedModeOptionText: { color: '#ffffff' },
  caseCompactCard: {
    alignItems: 'center',
    backgroundColor: '#1b1b1b',
    borderRadius: 22,
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 14,
    padding: 17,
  },
  caseCompactTitle: { alignItems: 'center', flexDirection: 'row', gap: 10 },
  caseCompactHeading: { color: '#f0f0f0', fontFamily: 'Carlito', fontSize: 14, fontWeight: '700' },
  caseCompactStatus: { color: '#999999', fontFamily: 'Carlito', fontSize: 11, marginTop: 4 },
  caseCompactValue: { color: '#ffc66d', fontFamily: 'Carlito', fontSize: 20, fontWeight: '700' },
  eqCard: { backgroundColor: '#1b1b1b', borderRadius: 28, marginTop: 0, padding: 20 },
  sectionHeadingRow: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
  sectionTitleWrap: { alignItems: 'center', flexDirection: 'row', gap: 8 },
  sectionHeading: { color: '#f4f7fb', fontFamily: 'Carlito', fontSize: 18, fontWeight: '700' },
  infoButton: { padding: 4 },
  guideCard: {
    alignItems: 'center',
    backgroundColor: '#1b1b1b',
    borderRadius: 22,
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 14,
    padding: 17,
  },
  featureCard: {
    alignItems: 'center',
    backgroundColor: '#1b1b1b',
    borderRadius: 22,
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 14,
    padding: 17,
  },
  featureTitleWrap: { alignItems: 'center', flex: 1, flexDirection: 'row' },
  featureIcon: { alignItems: 'center', backgroundColor: '#292431', borderRadius: 14, height: 42, justifyContent: 'center', width: 42 },
  featureCopy: { flex: 1, marginLeft: 12 },
  featureHeading: { color: '#f0f0f0', fontFamily: 'Carlito', fontSize: 14, fontWeight: '700' },
  featureDescription: { color: '#999999', fontFamily: 'Carlito', fontSize: 11, marginTop: 4 },
  gameToggle: { backgroundColor: '#3a3a3a', borderRadius: 18, height: 34, justifyContent: 'center', width: 58 },
  gameToggleActive: { backgroundColor: '#65449a' },
  gameToggleDisabled: { opacity: 0.55 },
  gameToggleThumb: { backgroundColor: '#f4f7fb', borderRadius: 13, height: 26, left: 4, position: 'absolute', top: 4, width: 26 },
  guideTitleWrap: { alignItems: 'center', flexDirection: 'row', gap: 10 },
  guideHeading: { color: '#f0f0f0', fontFamily: 'Carlito', fontSize: 14, fontWeight: '700' },
  guideCopy: { color: '#999999', fontFamily: 'Carlito', fontSize: 11, marginTop: 4 },
  headerBackButton: { alignItems: 'center', backgroundColor: '#1b1b1b', borderRadius: 18, height: 38, justifyContent: 'center', left: 0, position: 'absolute', top: -4, width: 38 },
  pageIntro: { alignItems: 'center', marginBottom: 24 },
  pageEyebrow: { color: '#9a8eb1', fontFamily: 'Carlito', fontSize: 10, fontWeight: '700', letterSpacing: 1.6 },
  pageTitle: { color: '#f4f7fb', fontFamily: 'Carlito', fontSize: 24, fontWeight: '700', marginTop: 5 },
  guideLead: { color: '#a6a6a6', fontFamily: 'Carlito', fontSize: 14, lineHeight: 21, marginBottom: 18, textAlign: 'center' },
  guideNotice: { alignItems: 'center', backgroundColor: '#292431', borderRadius: 18, flexDirection: 'row', gap: 11, marginBottom: 14, padding: 15 },
  guideNoticeText: { color: '#dcd2ef', flex: 1, fontFamily: 'Carlito', fontSize: 12, lineHeight: 18 },
  gestureSectionCard: { backgroundColor: '#1b1b1b', borderRadius: 24, marginBottom: 14, paddingHorizontal: 18 },
  gestureSectionTitle: { color: '#f4f7fb', fontFamily: 'Carlito', fontSize: 15, fontWeight: '700', paddingBottom: 3, paddingTop: 18 },
  gestureInstruction: { alignItems: 'center', borderTopColor: '#303030', borderTopWidth: 1, flexDirection: 'row', gap: 13, paddingVertical: 15 },
  gestureIcon: { alignItems: 'center', backgroundColor: '#292431', borderRadius: 14, height: 42, justifyContent: 'center', width: 42 },
  gestureInstructionCopy: { flex: 1 },
  gestureMetaRow: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
  gestureAction: { color: '#f4f7fb', fontFamily: 'Carlito', fontSize: 13, fontWeight: '700' },
  gestureSide: { color: '#a392bd', fontFamily: 'Carlito', fontSize: 10, fontWeight: '700', letterSpacing: 0.7, textTransform: 'uppercase' },
  gestureDescription: { color: '#9f9f9f', fontFamily: 'Carlito', fontSize: 12, lineHeight: 18, marginTop: 5 },
  controlLabelWrap: { alignItems: 'center', flexDirection: 'row', gap: 6, marginTop: 18 },
  controlLabel: { color: '#9eacbd', fontFamily: 'Carlito', fontSize: 12, fontWeight: '700' },
  chipRow: { gap: 8, paddingTop: 10 },
  controlChip: {
    backgroundColor: '#252525',
    borderColor: '#3b3b3b',
    borderRadius: 12,
    borderWidth: 1,
    paddingHorizontal: 14,
    paddingVertical: 11,
  },
  selectedControlChip: { backgroundColor: '#f4f4f4', borderColor: '#f4f4f4' },
  controlChipText: { color: '#bcbcbc', fontFamily: 'Carlito', fontSize: 12, fontWeight: '700' },
  selectedControlChipText: { color: '#151515' },
  soundProfileChip: { alignItems: 'center', backgroundColor: '#252525', borderColor: '#3b3b3b', borderRadius: 14, borderWidth: 1, flexDirection: 'row', gap: 8, paddingHorizontal: 13, paddingVertical: 11 },
  selectedSoundProfileChip: { backgroundColor: '#f4f4f4', borderColor: '#f4f4f4' },
  soundProfileText: { color: '#c8c1d8', fontFamily: 'Carlito', fontSize: 12, fontWeight: '700' },
  selectedSoundProfileText: { color: '#191919' },
  controlEmpty: { color: '#8d8d8d', fontFamily: 'Carlito', fontSize: 12, paddingTop: 11 },
  disconnectedContent: { alignItems: 'center', flex: 1, justifyContent: 'center', paddingBottom: 40 },
  scanMarkWrap: { alignItems: 'center', height: 124, justifyContent: 'center', width: 124 },
  scanHalo: { backgroundColor: '#6b4ab0', borderRadius: 62, height: 112, position: 'absolute', width: 112 },
  scanMark: {
    alignItems: 'center',
    backgroundColor: '#272727',
    borderColor: '#8d8d8d',
    borderRadius: 44,
    borderWidth: 1,
    height: 88,
    justifyContent: 'center',
    width: 88,
  },
  scanMarkText: { color: '#d8d8d8', fontSize: 54, fontWeight: '300', marginTop: -8 },
  disconnectedHeading: { color: '#f4f7fb', fontFamily: 'Carlito', fontSize: 24, fontWeight: '700', marginTop: 28, textAlign: 'center' },
  disconnectedCopy: { color: '#8997aa', fontFamily: 'Carlito', fontSize: 14, lineHeight: 21, marginTop: 12, maxWidth: 290, textAlign: 'center' },
  primaryButton: { backgroundColor: '#f4f4f4', borderRadius: 14, marginTop: 28, paddingHorizontal: 28, paddingVertical: 16 },
  disabledButton: { opacity: 0.65 },
  primaryButtonText: { color: '#171717', fontFamily: 'Carlito', fontSize: 14, fontWeight: '800', textAlign: 'center' },
  modalBackdrop: { alignItems: 'center', backgroundColor: 'rgba(0, 0, 0, 0.72)', flex: 1, justifyContent: 'center', padding: 22 },
  modalCard: {
    backgroundColor: '#202020',
    borderRadius: 26,
    elevation: 16,
    maxWidth: 430,
    padding: 22,
    shadowColor: '#000000',
    shadowOffset: { height: 8, width: 0 },
    shadowOpacity: 0.35,
    shadowRadius: 20,
    width: '100%',
  },
  modalAccent: { backgroundColor: '#70df90', borderRadius: 3, height: 4, marginBottom: 20, width: 42 },
  soundModalAccent: { backgroundColor: '#d8c3ff' },
  modalHeader: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between', marginBottom: 18 },
  modalTitle: { color: '#f4f7fb', fontFamily: 'Carlito', fontSize: 19, fontWeight: '700' },
  modalClose: { backgroundColor: '#2b2b2b', borderRadius: 18, padding: 7 },
  modalOption: { alignItems: 'flex-start', flexDirection: 'row', gap: 12, marginTop: 15 },
  modalOptionCopy: { flex: 1 },
  modalOptionTitle: { color: '#f4f7fb', fontFamily: 'Carlito', fontSize: 13, fontWeight: '700' },
  modalOptionText: { color: '#a6a6a6', fontFamily: 'Carlito', fontSize: 12, lineHeight: 18, marginTop: 4 },
  modalIntro: { color: '#a6a6a6', fontFamily: 'Carlito', fontSize: 12, lineHeight: 18, marginBottom: 8 },
  modalCallout: { alignItems: 'flex-start', backgroundColor: '#292431', borderRadius: 16, flexDirection: 'row', gap: 10, marginTop: 10, padding: 14 },
  modalCalloutText: { color: '#dcd2ef', flex: 1, fontFamily: 'Carlito', fontSize: 12, lineHeight: 18 },
  gestureRow: { alignItems: 'center', borderTopColor: '#353535', borderTopWidth: 1, flexDirection: 'row', paddingVertical: 14 },
  gestureCount: { color: '#d8c3ff', fontFamily: 'Carlito', fontSize: 13, fontWeight: '700', width: 62 },
  gestureText: { color: '#eeeeee', fontFamily: 'Carlito', fontSize: 12 },
  modalFootnote: { color: '#858585', fontFamily: 'Carlito', fontSize: 10, lineHeight: 16, marginTop: 8 },
  renameLabel: { color: '#f4f7fb', fontFamily: 'Carlito', fontSize: 12, fontWeight: '700', marginBottom: 7 },
  renameInput: { backgroundColor: '#2b2b2b', borderColor: '#555555', borderRadius: 13, borderWidth: 1, color: '#f4f7fb', fontFamily: 'Carlito', fontSize: 16, paddingHorizontal: 13, paddingVertical: 11 },
  renamePreview: { color: '#d8c3ff', fontFamily: 'Carlito', fontSize: 17, fontWeight: '700', marginTop: 16 },
  renameCounter: { alignSelf: 'flex-end', color: '#858585', fontFamily: 'Carlito', fontSize: 11, fontWeight: '700', marginTop: 8 },
  renameCounterError: { color: '#ff7777' },
  renameFeedback: { color: '#ffb86b', fontFamily: 'Carlito', fontSize: 11, lineHeight: 17, marginTop: 12 },
  renameSuccess: { color: '#70df90' },
  renameAction: { alignSelf: 'stretch', marginTop: 20 },
  footer: { color: '#666666', fontSize: 11, textAlign: 'center' },
});
