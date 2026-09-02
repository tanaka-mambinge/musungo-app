declare module '@tabler/icons-react-native/*' {
  import type { ForwardRefExoticComponent } from 'react';
  import type { SvgProps } from 'react-native-svg';

  const Icon: ForwardRefExoticComponent<
    SvgProps & {
      size?: string | number;
      strokeWidth?: string | number;
      title?: string;
    }
  >;

  export default Icon;
}
