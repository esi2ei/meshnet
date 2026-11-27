use anchor_lang::prelude::*;
use anchor_spl::{
    associated_token::AssociatedToken,
    token::{self, CloseAccount, Mint, Token, TokenAccount, TransferChecked},
};

declare_id!("Fg6PaFpoGXkYsidMpWxTWqZ7FEfcYkgMQhgX5j4mYp");

const CONFIG_SEED: &[u8] = b"config";
const ORDER_SEED: &[u8] = b"order";
const MAX_ORDER_LIFETIME: i64 = 30 * 24 * 60 * 60;

#[program]
pub mod meshnet_escrow {
    use super::*;

    pub fn initialize_config(
        ctx: Context<InitializeConfig>,
        usdc_mint: Pubkey,
        arbitrator: Pubkey,
    ) -> Result<()> {
        require!(usdc_mint != Pubkey::default(), EscrowError::InvalidMint);
        require!(arbitrator != Pubkey::default(), EscrowError::InvalidArbitrator);
        let config = &mut ctx.accounts.config;
        config.admin = ctx.accounts.admin.key();
        config.arbitrator = arbitrator;
        config.usdc_mint = usdc_mint;
        config.bump = ctx.bumps.config;
        Ok(())
    }

    pub fn open_order(
        ctx: Context<OpenOrder>,
        order_id: [u8; 16],
        amount: u64,
        expires_at: i64,
    ) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        require!(amount > 0, EscrowError::InvalidAmount);
        require!(expires_at > now, EscrowError::InvalidExpiry);
        require!(expires_at <= now + MAX_ORDER_LIFETIME, EscrowError::ExpiryTooFar);
        require!(ctx.accounts.provider.key() != ctx.accounts.buyer.key(), EscrowError::InvalidProvider);

        let order = &mut ctx.accounts.order;
        order.order_id = order_id;
        order.buyer = ctx.accounts.buyer.key();
        order.provider = ctx.accounts.provider.key();
        order.arbitrator = ctx.accounts.config.arbitrator;
        order.mint = ctx.accounts.mint.key();
        order.amount = amount;
        order.expires_at = expires_at;
        order.status = OrderStatus::Open;
        order.bump = ctx.bumps.order;

        let cpi = TransferChecked {
            from: ctx.accounts.buyer_token.to_account_info(),
            mint: ctx.accounts.mint.to_account_info(),
            to: ctx.accounts.vault.to_account_info(),
            authority: ctx.accounts.buyer.to_account_info(),
        };
        token::transfer_checked(
            CpiContext::new(ctx.accounts.token_program.to_account_info(), cpi),
            amount,
            ctx.accounts.mint.decimals,
        )?;
        emit!(OrderOpened {
            order: order.key(),
            buyer: order.buyer,
            provider: order.provider,
            amount,
            expires_at,
        });
        Ok(())
    }

    /// Release requires both buyer and provider signatures.
    pub fn release(ctx: Context<SettleOrder>) -> Result<()> {
        require_keys_eq!(ctx.accounts.buyer.key(), ctx.accounts.order.buyer, EscrowError::WrongBuyer);
        require_keys_eq!(ctx.accounts.provider.key(), ctx.accounts.order.provider, EscrowError::WrongProvider);
        settle_to_provider(ctx)
    }

    /// The configured arbitrator may release funds after a dispute decision.
    pub fn arbitrate_release(ctx: Context<ArbitrateOrder>) -> Result<()> {
        require_keys_eq!(ctx.accounts.arbitrator.key(), ctx.accounts.order.arbitrator, EscrowError::WrongArbitrator);
        settle_to_provider(ctx.accounts.into_settle())
    }

    /// Buyer can reclaim funds only after the immutable expiry timestamp.
    pub fn refund_expired(ctx: Context<RefundOrder>) -> Result<()> {
        require_keys_eq!(ctx.accounts.buyer.key(), ctx.accounts.order.buyer, EscrowError::WrongBuyer);
        require!(Clock::get()?.unix_timestamp >= ctx.accounts.order.expires_at, EscrowError::NotExpired);
        settle_to_buyer(ctx)
    }

    /// The configured arbitrator may refund the buyer after a dispute decision.
    pub fn arbitrate_refund(ctx: Context<ArbitrateRefund>) -> Result<()> {
        require_keys_eq!(ctx.accounts.arbitrator.key(), ctx.accounts.order.arbitrator, EscrowError::WrongArbitrator);
        settle_to_buyer(ctx.into_refund())
    }
}

fn settle_to_provider(ctx: Context<SettleOrder>) -> Result<()> {
    require!(ctx.accounts.order.status == OrderStatus::Open, EscrowError::AlreadySettled);
    require_keys_eq!(ctx.accounts.mint.key(), ctx.accounts.order.mint, EscrowError::InvalidMint);
    require!(ctx.accounts.vault.amount == ctx.accounts.order.amount, EscrowError::VaultBalanceMismatch);
    let order_key = ctx.accounts.order.key();
    let bump = [ctx.accounts.order.bump];
    let seeds: &[&[u8]] = &[
        ORDER_SEED,
        ctx.accounts.order.buyer.as_ref(),
        ctx.accounts.order.order_id.as_ref(),
        &bump,
    ];
    let signer = &[seeds];
    let amount = ctx.accounts.order.amount;
    let cpi = TransferChecked {
        from: ctx.accounts.vault.to_account_info(),
        mint: ctx.accounts.mint.to_account_info(),
        to: ctx.accounts.provider_token.to_account_info(),
        authority: ctx.accounts.order.to_account_info(),
    };
    token::transfer_checked(
        CpiContext::new_with_signer(ctx.accounts.token_program.to_account_info(), cpi, signer),
        amount,
        ctx.accounts.mint.decimals,
    )?;
    close_vault(
        &ctx.accounts.token_program,
        &ctx.accounts.vault,
        &ctx.accounts.buyer.to_account_info(),
        &ctx.accounts.order.to_account_info(),
        signer,
    )?;
    ctx.accounts.order.status = OrderStatus::Released;
    emit!(OrderSettled { order: order_key, status: OrderStatus::Released, amount });
    Ok(())
}

fn settle_to_buyer(ctx: Context<RefundOrder>) -> Result<()> {
    require!(ctx.accounts.order.status == OrderStatus::Open, EscrowError::AlreadySettled);
    require_keys_eq!(ctx.accounts.mint.key(), ctx.accounts.order.mint, EscrowError::InvalidMint);
    let order_key = ctx.accounts.order.key();
    let bump = [ctx.accounts.order.bump];
    let seeds: &[&[u8]] = &[
        ORDER_SEED,
        ctx.accounts.order.buyer.as_ref(),
        ctx.accounts.order.order_id.as_ref(),
        &bump,
    ];
    let signer = &[seeds];
    let amount = ctx.accounts.order.amount;
    let cpi = TransferChecked {
        from: ctx.accounts.vault.to_account_info(),
        mint: ctx.accounts.mint.to_account_info(),
        to: ctx.accounts.buyer_token.to_account_info(),
        authority: ctx.accounts.order.to_account_info(),
    };
    token::transfer_checked(
        CpiContext::new_with_signer(ctx.accounts.token_program.to_account_info(), cpi, signer),
        amount,
        ctx.accounts.mint.decimals,
    )?;
    close_vault(
        &ctx.accounts.token_program,
        &ctx.accounts.vault,
        &ctx.accounts.buyer.to_account_info(),
        &ctx.accounts.order.to_account_info(),
        signer,
    )?;
    ctx.accounts.order.status = OrderStatus::Refunded;
    emit!(OrderSettled { order: order_key, status: OrderStatus::Refunded, amount });
    Ok(())
}

fn close_vault<'info>(
    token_program: &Program<'info, Token>,
    vault: &Account<'info, TokenAccount>,
    destination: &AccountInfo<'info>,
    authority: &AccountInfo<'info>,
    signer: &[&[&[u8]]],
) -> Result<()> {
    let cpi = CloseAccount {
        account: vault.to_account_info(),
        destination: destination.clone(),
        authority: authority.clone(),
    };
    token::close_account(CpiContext::new_with_signer(token_program.to_account_info(), cpi, signer))
}

fn settle_to_provider_arbitrated(ctx: Context<ArbitrateOrder>) -> Result<()> {
    require_keys_eq!(ctx.accounts.arbitrator.key(), ctx.accounts.order.arbitrator, EscrowError::WrongArbitrator);
    require!(ctx.accounts.order.status == OrderStatus::Open, EscrowError::AlreadySettled);
    require!(ctx.accounts.vault.amount == ctx.accounts.order.amount, EscrowError::VaultBalanceMismatch);
    let key = ctx.accounts.order.key();
    let bump = [ctx.accounts.order.bump];
    let seeds: &[&[u8]] = &[ORDER_SEED, ctx.accounts.order.buyer.as_ref(), ctx.accounts.order.order_id.as_ref(), &bump];
    let signer = &[seeds];
    let amount = ctx.accounts.order.amount;
    let transfer = TransferChecked {
        from: ctx.accounts.vault.to_account_info(),
        mint: ctx.accounts.mint.to_account_info(),
        to: ctx.accounts.provider_token.to_account_info(),
        authority: ctx.accounts.order.to_account_info(),
    };
    token::transfer_checked(CpiContext::new_with_signer(ctx.accounts.token_program.to_account_info(), transfer, signer), amount, ctx.accounts.mint.decimals)?;
    let close = CloseAccount {
        account: ctx.accounts.vault.to_account_info(),
        destination: ctx.accounts.buyer.to_account_info(),
        authority: ctx.accounts.order.to_account_info(),
    };
    token::close_account(CpiContext::new_with_signer(ctx.accounts.token_program.to_account_info(), close, signer))?;
    emit!(OrderSettled { order: key, status: OrderStatus::Released, amount });
    Ok(())
}

fn settle_to_buyer_arbitrated(ctx: Context<ArbitrateRefund>) -> Result<()> {
    require_keys_eq!(ctx.accounts.arbitrator.key(), ctx.accounts.order.arbitrator, EscrowError::WrongArbitrator);
    require!(ctx.accounts.order.status == OrderStatus::Open, EscrowError::AlreadySettled);
    require!(ctx.accounts.vault.amount == ctx.accounts.order.amount, EscrowError::VaultBalanceMismatch);
    let key = ctx.accounts.order.key();
    let bump = [ctx.accounts.order.bump];
    let seeds: &[&[u8]] = &[ORDER_SEED, ctx.accounts.order.buyer.as_ref(), ctx.accounts.order.order_id.as_ref(), &bump];
    let signer = &[seeds];
    let amount = ctx.accounts.order.amount;
    let transfer = TransferChecked {
        from: ctx.accounts.vault.to_account_info(),
        mint: ctx.accounts.mint.to_account_info(),
        to: ctx.accounts.buyer_token.to_account_info(),
        authority: ctx.accounts.order.to_account_info(),
    };
    token::transfer_checked(CpiContext::new_with_signer(ctx.accounts.token_program.to_account_info(), transfer, signer), amount, ctx.accounts.mint.decimals)?;
    let close = CloseAccount {
        account: ctx.accounts.vault.to_account_info(),
        destination: ctx.accounts.buyer.to_account_info(),
        authority: ctx.accounts.order.to_account_info(),
    };
    token::close_account(CpiContext::new_with_signer(ctx.accounts.token_program.to_account_info(), close, signer))?;
    emit!(OrderSettled { order: key, status: OrderStatus::Refunded, amount });
    Ok(())
}

#[derive(Accounts)]
pub struct InitializeConfig<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    #[account(init, payer = admin, space = 8 + Config::INIT_SPACE, seeds = [CONFIG_SEED], bump)]
    pub config: Account<'info, Config>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(order_id: [u8; 16])]
pub struct OpenOrder<'info> {
    #[account(mut)]
    pub buyer: Signer<'info>,
    /// CHECK: Provider is stored as an immutable public key; token destination is constrained to its ATA.
    pub provider: UncheckedAccount<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump, has_one = usdc_mint)]
    pub config: Account<'info, Config>,
    #[account(
        init, payer = buyer, space = 8 + EscrowOrder::INIT_SPACE,
        seeds = [ORDER_SEED, buyer.key().as_ref(), order_id.as_ref()], bump
    )]
    pub order: Account<'info, EscrowOrder>,
    #[account(address = config.usdc_mint)]
    pub usdc_mint: Account<'info, Mint>,
    #[account(mut, associated_token::mint = usdc_mint, associated_token::authority = buyer)]
    pub buyer_token: Account<'info, TokenAccount>,
    #[account(init, payer = buyer, associated_token::mint = usdc_mint, associated_token::authority = order)]
    pub vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct SettleOrder<'info> {
    #[account(mut)]
    pub buyer: Signer<'info>,
    pub provider: Signer<'info>,
    #[account(
        mut, close = buyer,
        seeds = [ORDER_SEED, order.buyer.as_ref(), order.order_id.as_ref()], bump = order.bump,
        has_one = buyer, has_one = provider
    )]
    pub order: Account<'info, EscrowOrder>,
    #[account(address = order.mint)]
    pub mint: Account<'info, Mint>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = order, address = vault.key())]
    pub vault: Account<'info, TokenAccount>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = provider)]
    pub provider_token: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

#[derive(Accounts)]
pub struct ArbitrateOrder<'info> {
    pub arbitrator: Signer<'info>,
    /// CHECK: Immutable buyer address stored in the order; receives rent refunds only.
    #[account(mut, address = order.buyer)]
    pub buyer: UncheckedAccount<'info>,
    pub provider: Signer<'info>,
    #[account(
        mut, close = buyer,
        seeds = [ORDER_SEED, order.buyer.as_ref(), order.order_id.as_ref()], bump = order.bump,
        has_one = provider
    )]
    pub order: Account<'info, EscrowOrder>,
    #[account(address = order.mint)]
    pub mint: Account<'info, Mint>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = order)]
    pub vault: Account<'info, TokenAccount>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = provider)]
    pub provider_token: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

#[derive(Accounts)]
pub struct RefundOrder<'info> {
    #[account(mut)]
    pub buyer: Signer<'info>,
    #[account(
        mut, close = buyer,
        seeds = [ORDER_SEED, order.buyer.as_ref(), order.order_id.as_ref()], bump = order.bump,
        has_one = buyer
    )]
    pub order: Account<'info, EscrowOrder>,
    #[account(address = order.mint)]
    pub mint: Account<'info, Mint>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = order)]
    pub vault: Account<'info, TokenAccount>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = buyer)]
    pub buyer_token: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

#[derive(Accounts)]
pub struct ArbitrateRefund<'info> {
    pub arbitrator: Signer<'info>,
    #[account(mut)]
    /// CHECK: Immutable buyer address stored in the order; receives the refund and rent.
    #[account(mut, address = order.buyer)]
    pub buyer: UncheckedAccount<'info>,
    #[account(
        mut, close = buyer,
        seeds = [ORDER_SEED, order.buyer.as_ref(), order.order_id.as_ref()], bump = order.bump
    )]
    pub order: Account<'info, EscrowOrder>,
    #[account(address = order.mint)]
    pub mint: Account<'info, Mint>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = order)]
    pub vault: Account<'info, TokenAccount>,
    #[account(mut, associated_token::mint = mint, associated_token::authority = buyer)]
    pub buyer_token: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

#[account]
#[derive(InitSpace)]
pub struct Config {
    pub admin: Pubkey,
    pub arbitrator: Pubkey,
    pub usdc_mint: Pubkey,
    pub bump: u8,
}

#[account]
#[derive(InitSpace)]
pub struct EscrowOrder {
    pub order_id: [u8; 16],
    pub buyer: Pubkey,
    pub provider: Pubkey,
    pub arbitrator: Pubkey,
    pub mint: Pubkey,
    pub amount: u64,
    pub expires_at: i64,
    pub status: OrderStatus,
    pub bump: u8,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace)]
pub enum OrderStatus { Open, Released, Refunded }

#[event]
pub struct OrderOpened { pub order: Pubkey, pub buyer: Pubkey, pub provider: Pubkey, pub amount: u64, pub expires_at: i64 }
#[event]
pub struct OrderSettled { pub order: Pubkey, pub status: OrderStatus, pub amount: u64 }

#[error_code]
pub enum EscrowError {
    #[msg("Invalid USDC mint")] InvalidMint,
    #[msg("Invalid arbitrator")] InvalidArbitrator,
    #[msg("Amount must be greater than zero")] InvalidAmount,
    #[msg("Expiry must be in the future")] InvalidExpiry,
    #[msg("Expiry cannot exceed 30 days")] ExpiryTooFar,
    #[msg("Buyer and provider must differ")] InvalidProvider,
    #[msg("Wrong buyer signer")] WrongBuyer,
    #[msg("Wrong provider signer")] WrongProvider,
    #[msg("Wrong arbitrator signer")] WrongArbitrator,
    #[msg("Order is already settled")] AlreadySettled,
    #[msg("Order has not expired")] NotExpired,
    #[msg("Vault balance differs from immutable order amount")] VaultBalanceMismatch,
}
