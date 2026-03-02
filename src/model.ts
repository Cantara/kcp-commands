export interface CommandFlag {
  flag: string;
  description: string;
  use_when?: string;
}

export interface PreferredInvocation {
  invocation: string;
  use_when: string;
}

export interface NoisePattern {
  pattern: string;
  reason?: string;
}

export interface OutputSchema {
  enable_filter: boolean;
  noise_patterns?: NoisePattern[];
  max_lines?: number;
  truncation_message?: string;
}

export interface CommandSyntax {
  usage?: string;
  key_flags: CommandFlag[];
  preferred_invocations?: PreferredInvocation[];
}

export interface CommandManifest {
  command: string;
  subcommand?: string;
  platform?: 'linux' | 'darwin' | 'all';
  description: string;
  syntax: CommandSyntax;
  output_schema?: OutputSchema;
  generated?: boolean;
  generated_at?: string;
}
